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
	val actorSystem = ActorSystem("Ants", ConfigFactory.parseMap(HashMap[String,AnyRef](("akka.scheduler.tick-duration" -> "10ms"))))
	
	/** Initial actor launching all others. */
	val graph = actorSystem.actorOf(Props[GraphActor], name="graph")

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
  * edge, ect.). */
class AntSprite(id:String, manager:SpriteManager, initialPosition:Values) extends Sprite(id, manager, initialPosition) {	
	import AntSprite._

	/** Position along the edge in percents. */
	var pos = 0.0
	
	/** Speed, set when crossing an edge according to the edge length. */
	var speed = 0.0
	
	/** True if on the way back. */
	var goingBack = false

	/** The actor and the real ant behavior. */
	var controller:ActorRef = null

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

	/** Utility method, end-point node of the current edge (depends on the direction of the ant). */
	def endPoint():Node = {
		if(goingBack)
		     attachment.asInstanceOf[Edge].getSourceNode.asInstanceOf[Node]
		else attachment.asInstanceOf[Edge].getTargetNode.asInstanceOf[Node]
	}
}


// -- Graph Actor ----------------------------------------------------------------------------


/** Messages the graph actor can receive. */
object GraphActor {
	/** Start the graph environment actor and all ant sub-actors. The graph is
	  * given as a resource. If null, the "/TwoBridges.dgs" resource is loaded.
	  * The number of ants is also given and defaults to 10. */
	case class Start(graphResource:String = "/TwoBridges.dgs", antCount:Int = 10)

	/** The ant `antId` is now on edge `edgeId` at the start of it.
	  * Most often this message comes after the graph sent a [[Ant.AtIntersection]]
	  * message. */
	case class AntCrosses(antId:String, edgeId:String, pheromon:Double=0.0)

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
	var graph:SingleGraph = null
	
	/** GUI. */
	var viewer:Viewer = null

	/** Data channel from the GUI. */
	var fromViewer:ViewerPipe = null

	/** All ants representations. */
	var sprites:SpriteManager = null

	/** Shortcut to the nest node. */
	var nest:Node = null

	/** Maximum number of ants. */
	var antCount:Int = 0

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

		graph.getEachEdge.foreach { edge:Edge => edge.addAttribute(Ph, (Ant.minPh).asInstanceOf[AnyRef]) }
	}

	/** Utility method to compute all the possible edges to explore from a given node.
	  * Only outgoing edges are selected. The returned array contains pairs of edge
	  * identifiers and their lengths. */
	protected def possibleEdges(node:Node):Array[(String,Double,Double)] = {
		(node.getLeavingEdgeSet[Edge].map { edge =>
			(edge.getId, edge.getNumber(Ph), GraphPosLengthUtils.edgeLength(edge)) 
		}).toArray
	}		

	/** Utility method to get the pheromone level on an edge. */
	protected def getPh(edge:Edge):Double = edge.getNumber(Ph)

	/** Utility method to store some pheromone quantity `ph` on an edge. */
	protected def updatePh(ph:Double, edge:Edge) {
		edge.setAttribute(Ph, ph.asInstanceOf[AnyRef])
		edge.setAttribute("ui.label", "%.2f".format(ph))
		edge.setAttribute("ui.color", (ph/Ant.maxPh).asInstanceOf[AnyRef])
	}

	/** Utility method to read the phromone level of an edge and add to it the given
	  * quantity `ph`. */
	protected def dropPheromone(ph:Double, edge:Edge) {
		var phe = ph + getPh(edge)
		if(phe > Ant.maxPh) phe = Ant.maxPh
		updatePh(phe, edge)
	}

	/** Create a new ant if the total count is not reached. */
	protected def hatchAnts() {
		if(sprites.getSpriteCount < antCount) {
			hatchAnt
		}
	}

	/** Create an ant on the nest. */
	protected def hatchAnt() {
		val id     = "ant%d".format(sprites.getSpriteCount)
		val sprite = sprites.addSprite(id).asInstanceOf[AntSprite]

		sprite.controller = context.actorOf(Props[Ant], name=id)
		sprite.controller ! Ant.AtIntersection(possibleEdges(nest))
	}

	protected def nodePosition(node:Node, x:Int, y:Int) {
		node.setAttribute("xy", x.asInstanceOf[java.lang.Integer], y.asInstanceOf[java.lang.Integer]) 
	}

	/** Apply evaporation on each edge. */
	protected def evaporate() {
		graph.getEachEdge.foreach { edge:Edge =>
			var ph = getPh(edge)
			ph *= Ant.evaporation
			if(ph < Ant.minPh) ph = Ant.minPh
			updatePh(ph, edge)
		}
	}

	/** Make the ant representation move, detect when ants reached intersections,
	  * the nest or the food and send messages to their actor to notify these events. */
	protected def moveSprites() {
		sprites.iterator.foreach { sprite =>
			val antSprite = sprite.asInstanceOf[AntSprite]
			if(antSprite.run) {
				if(antSprite.goingBack) {
					if(antSprite.endPoint.hasAttribute("nest")) {
						antSprite.controller ! Ant.AtNest
					} else {
						antSprite.controller ! Ant.AtIntersection(null)
					}
				} else {
					if(antSprite.endPoint.hasAttribute("food")) {
						antSprite.controller ! Ant.AtFood
					} else {
						val edges = possibleEdges(antSprite.endPoint)

						if(edges.length > 0)
						     antSprite.controller ! Ant.AtIntersection(edges)
						else antSprite.controller ! Ant.Lost
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
			hatchAnts
			evaporate
			moveSprites
		}
		case AntGoesExploring(antId) => {
			val antSprite = sprites.getSprite(antId).asInstanceOf[AntSprite]
			antSprite.goingBack = false
			antSprite.removeAttribute("ui.class")
			antSprite.controller ! Ant.AtIntersection(possibleEdges(nest))
		}
		case AntGoesBack(antId) => {
			val antSprite = sprites.getSprite(antId).asInstanceOf[AntSprite]
			antSprite.goingBack = true
			antSprite.addAttribute("ui.class", "back")
			antSprite.controller ! Ant.AtIntersection(null)
		}
		case AntCrosses(antId, edgeId, drop) => {
			fromViewer.pump
			val length = GraphPosLengthUtils.edgeLength(graph, edgeId)
			val sprite = sprites.getSprite(antId).asInstanceOf[AntSprite]

			sprite.cross(edgeId, length)

			if(drop > 0)
				dropPheromone(drop, sprite.getAttachment.asInstanceOf[Edge])
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

	var gamma = 3.0

	/** The ant reached an intersection. The data is a set of triplets (edge
	  * identifier, pheromone level, length). */
	case class AtIntersection(edges:Array[(String,Double,Double)])

	/** The ant reached a food source. */
	case class AtFood()

	/** The ant reached the nest. */
	case class AtNest()

	/** The ant reached a dead-end. */
	case class Lost()
}


/** Represents an ant and its behavior. */
class Ant extends Actor {
	import Ant._

	/** Actual path of the ant from the nest to the food. */
	var memory = Stack[String]()

	/** If true instead of choosing the next edge, we unstack
	  * the memory to follow the path used at start. */
	var goingBack = false

	var pathSize = 0.0

	/** Random generator. */
	var random = scala.util.Random

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
				edge = chosen._1
				pathSize += chosen._3
				memory.push(edge)
			}
			
			sender ! GraphActor.AntCrosses(self.path.name, edge, drop)
		}
		case AtFood => {
			goingBack = true
			sender ! GraphActor.AntGoesBack(self.path.name)
		}
		case AtNest => {
			pathSize = 0.0
			goingBack = false
			sender ! GraphActor.AntGoesExploring(self.path.name)
		}
		case Lost => {
			pathSize = 0.0
			memory.clear
			goingBack = false
			sender ! GraphActor.AntGoesExploring(self.path.name)
		}
		case _ => {
			println("Ant: WTF ?!")
		}
	}

	/** Choose the next edge to cross purely at random, each edge has
	  * uniform probability to be chosen. */
	def chooseNextEdgeRandom(edges:Array[(String,Double,Double)]):(String,Double,Double) = { edges(random.nextInt(edges.length)) }
	
	/** Choose the next edge to cross according to pheromones and lengths of the edges.
	  * The weight is compute from relative pheromone and length importance. The paramter
	  * [[Ant.Alpha]] and [[Ant.Beta]] allow to change these relative importance.
	  * The compute array of weights is then used to find the next edge using a biased
	  * fortune wheel. */
	def chooseNextEdge(edges:Array[(String,Double,Double)]):(String,Double,Double) = {
		var sum = 0.0
		var rnd = random.nextDouble

		// Compute the weights of each edge following Dorigo formula.

		var weights = edges.map { edge =>
			val weight = pow(edge._2, alpha) * pow(1/edge._3, beta)
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
				case None    => ("",0.0,0.0)
			}
		}
	}
}