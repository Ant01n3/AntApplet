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
	
	/** Actor system. We do a quick and dirty configuration. */
	protected val actorSystem = ActorSystem("Ants", ConfigFactory.parseMap(HashMap[String,AnyRef](("akka.scheduler.tick-duration" -> "10ms"))))
	
	/** Initial actor launching all others. */
	protected val graph = actorSystem.actorOf(Props[Environment], name="graph")

	args.length match {
		case 0 ⇒ graph ! Environment.Start("/FourBridges.dgs", 10)
		case 1 ⇒ graph ! Environment.Start(args(0), 10)
		case _ ⇒ graph ! Environment.Start(args(0), args(1).toInt)
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
	
	/** Node identifier of destination, allows to orient the ant representation when moving. */
	var to:Node = null

	/** If we are crossing the edge in the natural direction or not. */
	var direction = 1.0

	/** True if the ant is going back to the nest. */
	var returning = false

	/** The actor and the real ant behavior. */
	var actor:ActorRef = null

	/** Start walking along the edge, the direction and start point depends on the old `to` target. */
	def cross(edge:Edge, length:Double) {
		val source = edge.getSourceNode.asInstanceOf[Node]
		val target = edge.getTargetNode.asInstanceOf[Node]

		if(to == source) {
		    direction = 1.0
		    pos       = 0.0
		    to        = target
		} else {
			direction = -1.0
			pos       =  1.0
			to        = source
		}

		speed = (1.0 / (length/Ant.speed)) * direction
		
		attachToEdge(edge.getId)
		setPosition(pos)
	}

	/** Walk a step on the edge, returns true if arrived at the end. */
	def run():Boolean = {
		var arrived = false

		if(speed != 0) {
			pos += speed

			if(direction < 0) {
				if(pos <= 0) { pos = 0; arrived = true }
			} else {
				if(pos >= 1) { pos = 1; arrived = true }
			}

			setPosition(pos)
		}

		arrived
	}

	/** Change the appearance of the ant. */
	def exploring(on:Boolean) {
		returning = !on
		if(on)
		     removeAttribute("ui.class")
		else setAttribute("ui.class", "back")
	}

	/** Utility method, end-point node of the current edge (depends on the direction of the ant). */
	def endPoint():Node = to
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
		this(edge.getId, GraphPosLengthUtils.edgeLength(edge), edge.getAttribute(Environment.Ph).asInstanceOf[Pheromone].toArray)
	}

	/** Pheromone value for the given `antType`. */
	def ph(antType:Int):Double = pheromones(antType)
}


// -- Environment ----------------------------------------------------------------------------


/** Messages the environment can receive. */
object Environment {
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
	case class AntExploring(antId:String)

	/** The ant `antId` goes back to the nest. */
	case class AntReturning(antId:String)

	/** Pheromone attribute. */
	final val Ph = "ph"
}


/** Represents the environment of the ants under the form of a graph. Handles global
  * behaviors like evaporation. It also handle the GUI and the ants representation
  * (but not the ant behavior that is implemented in ant actors). */
class Environment() extends Actor {
	import Environment._

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

		nest = graph.getEachNode.find { node:Node ⇒ node.hasAttribute("nest") }.getOrElse {
			throw new RuntimeException("You must provide a node with attribute 'nest' in the graph")
		}

		if(graph.hasNumber("antCount"))     antCount        = graph.getNumber("antCount").toInt
		if(graph.hasNumber("antTypes"))     antTypes        = graph.getNumber("antTypes").toInt
		if(graph.hasNumber("evaporation"))  Ant.evaporation = graph.getNumber("evaporation")
		if(graph.hasNumber("alpha"))        Ant.alpha       = graph.getNumber("alpha")
		if(graph.hasNumber("beta"))         Ant.beta        = graph.getNumber("beta")
		if(graph.hasNumber("gamma"))		Ant.gamma       = graph.getNumber("gamma")
		if(graph.hasNumber("phDrop"))       Ant.phDrop      = graph.getNumber("phDrop")
		if(graph.hasNumber("minPh"))        Ant.minPh       = graph.getNumber("minPh")
		if(graph.hasNumber("maxPh"))        Ant.maxPh       = graph.getNumber("maxPh")
		if(graph.hasNumber("speed"))        Ant.speed       = graph.getNumber("speed")
		if(graph.hasAttribute("useTabu"))   Ant.useTabu     = true else Ant.useTabu = false
		if(graph.hasAttribute("noMemory"))  Ant.useMemory   = false else Ant.useMemory = true

		graph.getEachEdge.foreach { edge:Edge ⇒ edge.addAttribute(Ph, Pheromone(antTypes)) }
	}

	/** Utility method to retrieve the representation of an ant knowing its identifier. */
	protected def antRepresentation(id:String) = sprites.getSprite(id).asInstanceOf[AntSprite]

	/** Utility method to compute all the possible edges to explore leaving a given node. */
	protected def possibleEdgesForward(node:Node):Array[EdgePheromones] = {
		(node.getLeavingEdgeSet[Edge].map(edge ⇒ new EdgePheromones(edge))).toArray
	}		

	/** Utility method to compute all the possible edges to explore entering a given node. */
	protected def possibleEdgesBackward(node:Node):Array[EdgePheromones] = {
		(node.getEnteringEdgeSet[Edge].map(edge ⇒ new EdgePheromones(edge))).toArray
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

		sprite.to    = nest
		sprite.actor = context.actorOf(Props[Ant], name=id)
		sprite.actor ! Ant.AntType(n % antTypes)
		sprite.actor ! Ant.AtIntersection(possibleEdgesForward(nest))
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
		graph.getEachEdge.foreach { edge:Edge ⇒
			val p = edge.getAttribute(Ph).asInstanceOf[Pheromone]
			p.evaporate
			updateEdgeAppearance(edge, p.max)
		}
	}

	/** Make the ant representation move, detect when ants reached intersections,
	  * the nest or the food and send messages to their actor to notify these events. */
	protected def moveAntsRepresentations() {
		sprites.iterator.foreach { sprite ⇒
			val antSprite = sprite.asInstanceOf[AntSprite]
			if(antSprite.run) {
				if(antSprite.returning) {
					if(antSprite.endPoint.hasAttribute("nest")) {
						antSprite.actor ! Ant.AtNest
					} else {
						val edges = possibleEdgesBackward(antSprite.endPoint)

						if(edges.length > 0)
						     antSprite.actor ! Ant.AtIntersection(edges)
						else antSprite.actor ! Ant.Lost
					}
				} else {
					if(antSprite.endPoint.hasAttribute("food")) {
						antSprite.actor ! Ant.AtFood(foodType(antSprite.endPoint))
					} else {
						val edges = possibleEdgesForward(antSprite.endPoint)

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
		case Start(resource, antCount) ⇒ {
			start(resource, antCount)
		}
		case ReceiveTimeout ⇒ {
			fromViewer.pump
			hatchAntsIfNeeded
			evaporatePheromone
			moveAntsRepresentations
		}
		case AntExploring(antId) ⇒ {
			val antSprite = antRepresentation(antId)
			antSprite.to = nest
			antSprite.exploring(true)
			antSprite.actor ! Ant.AtIntersection(possibleEdgesForward(nest))
		}
		case AntReturning(antId) ⇒ {
			val antSprite = antRepresentation(antId)
			antSprite.exploring(false)
			antSprite.actor ! Ant.AtIntersection(possibleEdgesBackward(antSprite.to))
		}
		case AntCrosses(antId, edgeId, antType, drop) ⇒ {
			fromViewer.pump
			val edge   = graph.getEdge(edgeId).asInstanceOf[Edge]
			val length = GraphPosLengthUtils.edgeLength(edge)
			val sprite = antRepresentation(antId)

			sprite.cross(edge, length)

			if(drop > 0)
				dropPh(antType, drop, edge)
		}
		case _ ⇒ {
			throw new RuntimeException("Graph: WTF ??")
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

	/** Do ants have a memory allowing them to remember their path toward food and use
	  * it to go back at the nest ? */
	var useMemory = true

	/** Do ants use their memory (even if useMemory is false) as a tabu list in order
	  * to avoid visiting edges already visited when exploring for food. */
	var useTabu = false

	/** Multiply the weight of a tabu edge by this. Zero means the tabu edge will never
	  * be considered execpted if it is the only one possible. */
	var tabuEdgeWeight = 0.0

	/** Speed of ants. */
	var speed = 0.1

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


/** Represents an ant and its behavior.
  *
  * The ant has two modes "exploration" and "returning". It can memorize its
  * path either in exploration mode or not.
  * memory is  */
class Ant extends Actor {
	import Ant._

	/** True if we are looking for food. Else we are in "returning" mode. */
	protected var exploration = true

	/** Actual path of the ant from the nest to the food. This path is built
	  * only when in exploration mode */
	protected var memory = Stack[EdgePheromones]()

	/** Quantity of pheromone actually dropped on crossed edges. */
	protected var drop = 0.0

	/** The length of the path during exploration phase. */
	protected var pathLength = 0.0

	/** Ant type, changes the kind of pehomones used to choose edges. */
	protected var antType = 0

	/** Random generator. */
	protected var random = scala.util.Random

	/** Behavior. */
	def receive() = {
		case AntType(theAntType) ⇒ {
			antType = theAntType
		}
		case AtIntersection(edges) ⇒ {
			var edge:EdgePheromones = null

			if(exploration) {
				edge = chooseNextEdgeForward(edges)
				memorize(edge)
			} else {
				edge = chooseNextEdgeBackward(edges)
				if(!useMemory && useTabu)
					memorize(edge)
			}
			
			sender ! Environment.AntCrosses(id, edge.id, antType, drop)
		}
		case AtFood(nodeFoodType) ⇒ {
			exploration = false

			if(antType == nodeFoodType) {
				drop = dropQuantity
				sender ! Environment.AntReturning(id)
			} else {
				drop = 0.0
				sender ! Environment.AntReturning(id)
			}

			if(!useMemory && useTabu)
				resetMemory
		}
		case AtNest ⇒ {
			exploration = true
			resetMemory
			drop = 0.0
			sender ! Environment.AntExploring(id)
		}
		case Lost ⇒ {
			exploration = false
			drop = 0.0
			sender ! Environment.AntReturning(id)

			if(!useMemory && useTabu)
				resetMemory
		}
		case _ ⇒ {
			throw new RuntimeException("Ant: WTF ?!")
		}
	}

	/** Clear memory, path size, and ensure the ant is ready to start exploring. */
	protected def resetMemory() {
		pathLength = 0.0
		memory.clear
	}


	/** Push in memory the given `edge` and add its length to the path size. */
	protected def memorize(edge:EdgePheromones) {
		pathLength += edge.length
		if(Ant.useMemory)
			memory.push(edge)
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
	protected def chooseNextEdgeForward(edges:Array[EdgePheromones]):EdgePheromones = {
		var sum = 0.0
		var rnd = random.nextDouble

		// Compute the weights of each edge following Dorigo formula.

		var weights = edges.map { edge ⇒
			val weight = edgeWeight(edge)
			sum += weight
			(edge, weight)
		}

		if(sum <= 0) {
			chooseNextEdgeRandom(edges)
		} else {
			// Biased fortune wheel decision.

			sum *= rnd
			var tot = 0.0

			weights.find { edge ⇒ tot += edge._2; tot >= sum } match {
				case Some(e) ⇒ e._1
				case None    ⇒ new EdgePheromones("<none>", 0.0, null)
			}
		}
	}

	/** Choose the next edge to cross when boing back to the nest. Here depending
	  * on the presence or not of a memory, the ant will either select the next
	  * memorized edge to cross to go back following the forward path in reverser
	  * order, or it will select the path back using the same mechanism as for
	  * the forward path (using pheromone, edges lengths, etc.). */
	protected def chooseNextEdgeBackward(edges:Array[EdgePheromones]):EdgePheromones = {
		if(Ant.useMemory) {
			memory.pop
		} else {
			chooseNextEdgeForward(edges)
		}
	}

	/** Compute the weight of a potential edge. */
	protected def edgeWeight(edge:EdgePheromones):Double = {
		var weight = pow(edge.ph(antType), alpha) * (if(beta > 0) pow(1 / edge.length, beta) else 1.0)
		
		if(useTabu && isTabu(edge.id))
			weight *= tabuEdgeWeight

		weight
	}

	/** True if in exploration mode and the ??? */
	protected def isTabu(edgeId:String):Boolean = (exploration == false && memory.contains(edgeId))

	/** Quantity of pheromone to drop on edges. */
	protected def dropQuantity():Double = if(gamma <= 0) phDrop else phDrop / pow(pathLength, gamma)
}