package org.graphstream.ants

import scala.compat.Platform
import scala.collection.mutable.{HashMap, ArrayBuffer, Stack}
import org.graphstream.graph.{Node, Edge}
import org.graphstream.graph.implementations.SingleGraph
import org.graphstream.stream.file.FileSourceDGS
import org.graphstream.ui.swingViewer.{Viewer, ViewerPipe}
import org.graphstream.ui.graphicGraph.GraphPosLengthUtils
import org.graphstream.ui.graphicGraph.stylesheet.Values
import org.graphstream.ui.spriteManager.{SpriteManager, Sprite, SpriteFactory}
import com.typesafe.config.{ConfigFactory, Config}
import akka.actor.{Actor, Props, ActorSystem, ReceiveTimeout, ActorRef}
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import scala.language.postfixOps
import scala.math._


/** Launch the application. */
object AntsApplet extends App {
	
	/** Actor system. */
	protected val actorSystem = ActorSystem("Ants", ConfigFactory.parseMap(HashMap[String,AnyRef](("akka.scheduler.tick-duration" -> "10ms"))))
	
	/** Initial actor launching all others. */
	protected val graph = actorSystem.actorOf(Props[GraphActor], name="graph")

	args.length match {
		case 0 => graph ! GraphActor.Start("/FourBridges.dgs", 10)
		case 1 => graph ! GraphActor.Start(args(0), 10)
		case _ => graph ! GraphActor.Start(args(0), args(1).toInt)
	}
}


// -- Ant sprite (graphical representation) --------------------------------------------------


/** AntSprite companion object and sprite factory for the SpriteManger. */
object AntSprite extends SpriteFactory {
	override def newSprite(id:String, manager:SpriteManager, initialPosition:Values):Sprite = new AntSprite(id, manager, initialPosition)
}


/** Representation of an ant in the graph. Handle the fine displacement of the representation along
  * the edge, the speed according to edges lengths, but none of the real behavior (choice of the next
  * edge, ect.). The real behavior is implemented in the [[Ant]] class. */
class AntSprite(id:String, manager:SpriteManager, initialPosition:Values) extends Sprite(id, manager, initialPosition) {	
	import AntSprite._

	/** Position along the edge in percents. */
	protected var pos = 0.0
	
	/** Speed, set when crossing an edge according to the edge length. */
	protected var speed = 0.0
	
	/** True if on the way back. */
	var goingBack = false

	/** The actor and the real ant behavior. */
	var actor:ActorRef = null

	/** Start walking along the edge, the direction and start point depends on the `goingBack` flag. */
	def cross(edge:String, length:Double) {
		if(goingBack) {
			pos   =  1.0
			speed = -1.0 / (length/0.1)
		} else {
			pos   = 0.0
			speed = 1.0 / (length/0.1)
		}
		
		attachToEdge(edge)
		setPosition(pos)

	}

	/** Walk a step on the edge, returns true if arrived at the end. */
	def run():Boolean = {
		var arrived = false

		if(speed != 0) {

			pos += speed

			if(goingBack) {
				if(pos <= 0) { pos = 0; arrived = true }
			} else {
				if(pos >= 1) { pos = 1; arrived = true }
			}

			setPosition(pos)
		}

		arrived
	}

	def goBack(on:Boolean) {
		goingBack = on
		if(on)
		     setAttribute("ui.class", "back")
		else removeAttribute("ui.class")
	}

	/** Utility method, end-point node of the current edge (depends on the direction of the ant). */
	def endPoint():Node = {
		if(goingBack)
		     attachment.asInstanceOf[Edge].getSourceNode.asInstanceOf[Node]
		else attachment.asInstanceOf[Edge].getTargetNode.asInstanceOf[Node]
	}
}


// -- Pheromone ------------------------------------------------------------------------------


/** Represent a set of pheromone values to be stored on an edge.
  * As there can exist several ant types, there also can exist several
  * pheromone types. Therefore a pheromone object represents a set
  * of value, not only one. */
case class Pheromone(count:Int) {
	/** The pheromone values. */
	protected val ph = new Array[Double](count)

	init

	/** Maximum pheromone value on this edge. */
	protected var maxPh = 0.0

	/** Initialize all pheromones to [[Ant.minPh]]. */
	protected def init() {
		var i = 0
		while(i < count) { ph(i) = Ant.minPh; i += 1 }
		maxPh = Ant.minPh
	}

	/** Maximum pheromone value for this edge. */
	def max:Double = maxPh

	/** Multiply each pheromone type by the [[Ant.evaporation]] value. If the
	  * pheromone value is less than [[Ant.minPh]], it is reset to [[Ant.minPh]]. */
	def evaporate() {
		var i = 0
		maxPh = 0.0
		while(i < count) {
			ph(i) *= Ant.evaporation
			if(ph(i) < Ant.minPh) ph(i) = Ant.minPh
			if(ph(i) > maxPh) maxPh = ph(i)
			i += 1
		}
	}

	/** Add a pheromone `value` for a given `antType`. If the value becomes
	  * larger than [[Ant.maxPh]], it is reset to [[Ant.maxPh]]. */
	def drop(antType:Int, value:Double) {
		ph(antType) += value 
		if(ph(antType) > Ant.maxPh) ph(antType) = Ant.maxPh
		if(ph(antType) > maxPh) maxPh = ph(antType)
	}

	/** Pheromone value for the given `antType`. */
	def value(antType:Int):Double = ph(antType)

	/** Make a copy of the pheromones values under the form or an array of doubles. */
	def toArray():Array[Double] = ph.clone
}


/** Pheromone descriptor for an edge. This is sent to ants as a constant (ant do not act
  * directly on values stored on the graph). */
class EdgePheromones(val id:String, val length:Double, val pheromones:Array[Double]) {
	def this(edge:Edge) {
		this(edge.getId, GraphPosLengthUtils.edgeLength(edge), edge.getAttribute(GraphActor.Ph).asInstanceOf[Pheromone].toArray)
	}

	/** Pheromone value for the given `antType`. */
	def ph(antType:Int):Double = pheromones(antType)
}



// -- Graph Actor ----------------------------------------------------------------------------


/** Messages the graph actor can receive. */
object GraphActor {
	/** Start the graph environment actor and all ant sub-actors. The graph is
	  * given as a resource. If null, the "/TwoBridges.dgs" resource is loaded.
	  * The number of ants is also given and defaults to 10. */
	case class Start(graphResource:String = "/TwoBridges.dgs", antCount:Int = 10)

	/** The ant `antId` is now on edge `edgeId` at the start of it. Doing so it will
	  * deposit the given 'pheromon' quantity of pheromone on the edge. The `antType`
	  * allows to specify the kind of pheromone to drop.
	  * Most often this message comes after the graph sent a [[Ant.AtIntersection]]
	  * message. */
	case class AntCrosses(antId:String, edgeId:String, antType:Int=0, pheromon:Double=0.0)

	/** The ant `antId` starts exploring from the nest. */
	case class AntGoesExploring(antId:String)

	/** The ant `antId` goes back to the nest. */
	case class AntGoesBack(antId:String)

	/** Pheromone attribute. */
	final val Ph = "ph"
}


/** Represents the environment of the ants. Handles global behaviors like evaporation.
  * It also handle the GUI and the ants representation (but not the ant behavior that
  * is implemented in ant actors). */
class GraphActor() extends Actor {
	import GraphActor._

	/** Ant environment. */
	protected var graph:SingleGraph = null
	
	/** GUI. */
	protected var viewer:Viewer = null

	/** Data channel from the GUI. */
	protected var fromViewer:ViewerPipe = null

	/** All ants representations. */
	protected var sprites:SpriteManager = null

	/** Shortcut to the nest node. */
	protected var nest:Node = null

	/** Maximum number of ants. */
	protected var antCount:Int = 0

	/** Number of ant types. */
	protected var antTypes:Int = 1

	/** Start the actor and all ant sub-actors. */
	protected def start(resource:String, count:Int) {
		graph      = new SingleGraph("Ants")
		viewer     = graph.display(false)
		fromViewer = viewer.newViewerPipe()
        sprites    = new SpriteManager(graph)
        antCount   = count

        sprites.setSpriteFactory(AntSprite)

        fromViewer.addSink(graph)
		loadGraph(resource)
		fromViewer.pump
		context.setReceiveTimeout(40 milliseconds)	// Update the graph from the viewer every 40ms.
	}

	/** Load the given graph `resource` and initialize pheromones and the nest on it. 
	  * The nest must have identifier "Nest". */
	protected def loadGraph(resource:String) {
		var url = getClass.getResource(resource)
		var src = new FileSourceDGS

		src.addSink(graph)

		if(url eq null) {
			src.readAll(resource)
		} else {
			src.readAll(url)
		}

		src.removeSink(graph)			

		nest = graph.getEachNode.find { node:Node => node.hasAttribute("nest") }.getOrElse {
			throw new RuntimeException("You must provide a node with attribute 'nest' in the graph")
		}

		if(graph.hasNumber("antCount"))    antCount        = graph.getNumber("antCount").toInt
		if(graph.hasNumber("evaporation")) Ant.evaporation = graph.getNumber("evaporation")
		if(graph.hasNumber("alpha"))       Ant.alpha       = graph.getNumber("alpha")
		if(graph.hasNumber("beta"))        Ant.beta        = graph.getNumber("beta")
		if(graph.hasNumber("phDrop"))      Ant.phDrop      = graph.getNumber("phDrop")
		if(graph.hasNumber("minPh"))       Ant.minPh       = graph.getNumber("minPh")
		if(graph.hasNumber("maxPh"))       Ant.maxPh       = graph.getNumber("maxPh")
		if(graph.hasNumber("antTypes"))    antTypes        = graph.getNumber("antTypes").toInt

		graph.getEachEdge.foreach { edge:Edge => edge.addAttribute(Ph, Pheromone(antTypes)) }
	}

	/** Utility method to compute all the possible edges to explore from a given node.
	  * Only outgoing edges are selected. */
	protected def possibleEdges(node:Node):Array[EdgePheromones] = {
		(node.getLeavingEdgeSet[Edge].map(edge => new EdgePheromones(edge))).toArray
	}		

	/** Utility method to get the maximum pheromone level on an edge. The value
	  * returned is the maximum value for one of the ant types. */
	protected def getPh(edge:Edge):Double = edge.getAttribute(Ph).asInstanceOf[Pheromone].max

	/** Utility method to drop some pheromone quantity `ph` on an edge. */
	protected def dropPh(antType:Int, ph:Double, edge:Edge) {
		val p = edge.getAttribute(Ph).asInstanceOf[Pheromone]
		p.drop(antType, ph)
		updateEdgeAppearance(edge, p.max)
	}

	/** Utility method to change the label and color of an edge based on the given pheromone value. */
	protected def updateEdgeAppearance(edge:Edge, pheromone:Double) {
		edge.setAttribute("ui.label", "%.2f".format(pheromone))
		edge.setAttribute("ui.color", (pheromone/Ant.maxPh).asInstanceOf[AnyRef])
	}

	/** Create a new ant if the total count is not reached. */
	protected def hatchAntsIfNeeded() { if(sprites.getSpriteCount < antCount) { hatchAnt } }

	/** Create an ant on the nest. */
	protected def hatchAnt() {
		val n      = sprites.getSpriteCount
		val id     = "ant%d".format(n)
		val sprite = sprites.addSprite(id).asInstanceOf[AntSprite]

		sprite.actor = context.actorOf(Props[Ant], name=id)
		sprite.actor ! Ant.AntType(n % antTypes)
		sprite.actor ! Ant.AtIntersection(possibleEdges(nest))
	}

	/** Utility to move a `node` to position (`x`, `y`). */
	protected def nodePosition(node:Node, x:Int, y:Int) {
		node.setAttribute("xy", x.asInstanceOf[java.lang.Integer], y.asInstanceOf[java.lang.Integer]) 
	}

	/** Utility method to retrieve the food type of a node returns 0 by default if
	  * the attribute is not present. */
	protected def foodType(node:Node):Int = if(node.hasNumber("foodType")) { node.getNumber("foodType").toInt } else { 0 }

	/** Apply evaporation on each edge. */
	protected def evaporatePheromone() {
		graph.getEachEdge.foreach { edge:Edge =>
			val p = edge.getAttribute(Ph).asInstanceOf[Pheromone]
			p.evaporate
			updateEdgeAppearance(edge, p.max)
		}
	}

	/** Make the ant representation move, detect when ants reached intersections,
	  * the nest or the food and send messages to their actor to notify these events. */
	protected def moveAntsRepresentations() {
		sprites.iterator.foreach { sprite =>
			val antSprite = sprite.asInstanceOf[AntSprite]
			if(antSprite.run) {
				if(antSprite.goingBack) {
					if(antSprite.endPoint.hasAttribute("nest")) {
						antSprite.actor ! Ant.AtNest
					} else {
						antSprite.actor ! Ant.AtIntersection(null)
					}
				} else {
					if(antSprite.endPoint.hasAttribute("food")) {
						antSprite.actor ! Ant.AtFood(foodType(antSprite.endPoint))
					} else {
						val edges = possibleEdges(antSprite.endPoint)

						if(edges.length > 0)
						     antSprite.actor ! Ant.AtIntersection(edges)
						else antSprite.actor ! Ant.Lost
					}
				}
			}
		}
	}

	/** Behavior. */
	def receive() = {
		case Start(resource, antCount) => {
			start(resource, antCount)
		}
		case ReceiveTimeout => {
			fromViewer.pump
			hatchAntsIfNeeded
			evaporatePheromone
			moveAntsRepresentations
		}
		case AntGoesExploring(antId) => {
			val antSprite = sprites.getSprite(antId).asInstanceOf[AntSprite]
			antSprite.goBack(false)
			antSprite.actor ! Ant.AtIntersection(possibleEdges(nest))
		}
		case AntGoesBack(antId) => {
			val antSprite = sprites.getSprite(antId).asInstanceOf[AntSprite]
			antSprite.goBack(true)
			antSprite.actor ! Ant.AtIntersection(null)
		}
		case AntCrosses(antId, edgeId, antType, drop) => {
			fromViewer.pump
			val length = GraphPosLengthUtils.edgeLength(graph, edgeId)
			val sprite = sprites.getSprite(antId).asInstanceOf[AntSprite]

			sprite.cross(edgeId, length)

			if(drop > 0)
				dropPh(antType, drop, sprite.getAttachment.asInstanceOf[Edge])
		}
		case _ => {
			println("Graph: WTF ??")
		}
	}
}


// -- Ant Actor ---------------------------------------------------------------------------------------


/** Messages an ant can receive and some global behavior values. */
object Ant {
	/** Maximum pheromon level on edges. */
	var maxPh = 1.0

	/** Minimum pheromon level on edges. */
	var minPh =  0.1

	/** Pheromone conservation factor (not really evaporation, but as usual...). */
	var evaporation = 0.997

	/** Quantity of pheromone ants drop on edges. */
	var phDrop = 1.0

	/** Relative importance of pheromones when ants choose a path. */
	var alpha = 2.0

	/** Relative importance of edge length when ants choose a path (greedy algorithm). */
	var beta = 1.5

	/** How to augment the pheromone level of tracks depending on the track length. */
	var gamma = 3.0

	/** Specify the type of the ant (this changes the pheromone type). */
	case class AntType(antType:Int)

	/** The ant reached an intersection. */
	case class AtIntersection(edges:Array[EdgePheromones])

	/** The ant reached a food source. The parameter is an optional food type
	  * for ants to decide if they consume it or not depending on their ant type. */
	case class AtFood(foodType:Int=0)

	/** The ant reached the nest. */
	case class AtNest()

	/** The ant reached a dead-end. */
	case class Lost()
}


/** Represents an ant and its behavior. */
class Ant extends Actor {
	import Ant._

	/** Actual path of the ant from the nest to the food. */
	protected var memory = Stack[String]()

	/** If true instead of choosing the next edge, we unstack
	  * the memory to follow the path used at start. */
	protected var goingBack = false

	protected var pathSize = 0.0

	/** Random generator. */
	protected var random = scala.util.Random

	/** Ant type. */
	protected var antType = 0

	/** Behavior. */
	def receive() = {
		case AtIntersection(edges) => {
			var edge:String = null
			var drop:Double = 0.0

			if(goingBack) {
				edge = memory.pop
				drop = if(gamma <=0) phDrop else phDrop / pow(pathSize, gamma)
			} else {
				val chosen = chooseNextEdge(edges)
				edge       = chosen.id
				memorize(chosen)
			}
			
			sender ! GraphActor.AntCrosses(id, edge, antType, drop)
		}
		case AntType(theAntType) => {
			antType = theAntType
		}
		case AtFood(nodeFoodType) => {
			if(antType == nodeFoodType) {
				goingBack = true
				sender ! GraphActor.AntGoesBack(id)
			} else {
				resetMemory
				sender ! GraphActor.AntGoesExploring(id)
			}
		}
		case AtNest => {
			resetMemory
			sender ! GraphActor.AntGoesExploring(id)
		}
		case Lost => {
			resetMemory
			sender ! GraphActor.AntGoesExploring(id)
		}
		case _ => {
			println("Ant: WTF ?!")
		}
	}

	/** If the ant is lost, clear memory, path size, and ensure the ant is ready to start exploring. */
	protected def resetMemory() {
		goingBack = false
		pathSize  = 0.0
		memory.clear
	}

	/** Push in memory the given `edge` and add its length to the path size. */
	protected def memorize(edge:EdgePheromones) {
		pathSize += edge.length
		memory.push(edge.id)
	}

	/** Ant identifier. */
	protected def id:String = self.path.name

	/** Choose the next edge to cross purely at random, each edge has
	  * uniform probability to be chosen. */
	protected def chooseNextEdgeRandom(edges:Array[EdgePheromones]):EdgePheromones = { edges(random.nextInt(edges.length)) }
	
	/** Choose the next edge to cross according to pheromones and lengths of the edges using
	  * a biased fortune wheel.
	  * The weight is computed from relative pheromones and lengths importances. The parameters
	  * [[Ant.alpha]] and [[Ant.beta]] allow to change these relative importances.
	  * The computed array of weights is then used to find the next edge using a biased
	  * fortune wheel. */
	protected def chooseNextEdge(edges:Array[EdgePheromones]):EdgePheromones = {
		var sum = 0.0
		var rnd = random.nextDouble

		// Compute the weights of each edge following Dorigo formula.

		var weights = edges.map { edge =>
			val weight = pow(edge.ph(antType), alpha) * (if(beta > 0) pow(1 / edge.length, beta) else 1.0)
			sum += weight
			(edge, weight)
		}

		if(sum <= 0) {
			chooseNextEdgeRandom(edges)
		} else {
			// Biased fortune wheel decision.

			sum *= rnd
			var tot = 0.0

			weights.find { edge => tot += edge._2; tot >= sum } match {
				case Some(e) => e._1
				case None    => new EdgePheromones("<none>", 0.0, null)
			}
		}
	}
}