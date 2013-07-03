package org.graphstream.ants

import scala.compat.Platform
import scala.collection.mutable.{HashMap, ArrayBuffer, Stack}
import org.graphstream.graph.{Node, Edge}
import org.graphstream.graph.implementations.SingleGraph
import org.graphstream.ui.swingViewer.{Viewer, ViewerPipe}
import org.graphstream.ui.graphicGraph.GraphPosLengthUtils
import org.graphstream.ui.graphicGraph.stylesheet.Values
import org.graphstream.ui.spriteManager.{SpriteManager, Sprite, SpriteFactory}
import com.typesafe.config.{ConfigFactory, Config}
import akka.actor.{Actor, Props, ActorSystem, ReceiveTimeout, ActorRef}
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import scala.language.postfixOps


/** Launch the application. */
object AntsApplet extends App {
	
	/** Actor system. */
	val actorSystem = ActorSystem("Ants", ConfigFactory.parseMap(HashMap[String,AnyRef](("akka.scheduler.tick-duration" -> "10ms"))))
	
	/** Initial actor launching all others. */
	val graph = actorSystem.actorOf(Props[GraphActor], name="graph")
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

	/** End-point node of the current edge (depends on the direction of the ant). */
	def endPoint():Node = {
		if(goingBack)
		     attachment.asInstanceOf[Edge].getSourceNode.asInstanceOf[Node]
		else attachment.asInstanceOf[Edge].getTargetNode.asInstanceOf[Node]
	}
}


// -- Graph Actor ----------------------------------------------------------------------------


/** Messages the graph actor can receive. */
object GraphActor {
	/** The ant `antId` is now on edge `edgeId` at the start of it.
	  * Most often this message comes after the graph sent a [[Ant.AtIntersection]]
	  * message. */
	case class AntCrosses(antId:String, edgeId:String, pheromon:Double=0.0)

	/** The ant `antId` starts exploring from the nest. */
	case class AntGoExploring(antId:String)

	/** The ant `antId` goes back to the nest. */
	case class AntGoesBack(antId:String)
}


/** Represents the environment of the ants. Handles global behaviors like evaporation.
  * It also handle the GUI and the ants representation (but not the ant behavior that
  * is implemented in ant actors). */
class GraphActor extends Actor {
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

	/** Shortcut to the food node. */
	var food:Node = null

	init

	protected def init() {
		graph      = new SingleGraph("Ants")
		viewer     = graph.display(false)
		fromViewer = viewer.newViewerPipe()
        sprites    = new SpriteManager(graph)

        sprites.setSpriteFactory(AntSprite)

        fromViewer.addSink(graph)
		initGraph
		initAnts(20)
		fromViewer.pump
		context.setReceiveTimeout(40 milliseconds)	// Update the graph from the viewer every 40ms.
	}

	protected def initGraph() {
		nest       = graph.addNode("Nest")
		food       = graph.addNode("Food")
		val a:Node = graph.addNode("A")
		val b:Node = graph.addNode("B")
		val c:Node = graph.addNode("C")
		val d:Node = graph.addNode("D")
		val e:Node = graph.addNode("E")
		val f:Node = graph.addNode("F")
		val g:Node = graph.addNode("G")
		val h:Node = graph.addNode("H")

		nest.addAttribute("ui.class", "nest")
		nest.addAttribute("ui.label", "Nest")
		food.addAttribute("ui.class", "food")
		food.addAttribute("ui.label", "Food")

		val nesta:Edge = graph.addEdge("NestA", nest, a, true)
		val ab:Edge    = graph.addEdge("AB", a, b, true)
		val ac:Edge    = graph.addEdge("AC", a, c, true)
		val bd:Edge    = graph.addEdge("BD", b, d, true)
		val cd:Edge    = graph.addEdge("CD", c, d, true)
		val de:Edge    = graph.addEdge("DE", d, e, true)
		val ef:Edge    = graph.addEdge("EF", e, f, true)
		val eg:Edge    = graph.addEdge("EG", e, g, true)
		val fh:Edge    = graph.addEdge("FH", f, h, true)
		val gh:Edge    = graph.addEdge("GH", g, h, true)
		val hfood:Edge = graph.addEdge("HFood", h, food, true)

		nodePosition(nest, 0,  0)
		nodePosition(food, 0, -7)
		nodePosition(a,    0, -1)
		nodePosition(b,   -1, -2)
		nodePosition(c,    3, -2)
		nodePosition(d,    0, -3)
		nodePosition(e,    0, -4)
		nodePosition(f,   -3, -5)
		nodePosition(g,    1, -5)
		nodePosition(h,    0, -6)

		graph.getEachEdge.foreach { edge:Edge => edge.addAttribute("ph", (0.0).asInstanceOf[AnyRef]) }

		graph.addAttribute("ui.title", "Ants !")
		graph.addAttribute("ui.antialias")
		graph.addAttribute("ui.stylesheet", """
				node { fill-color: grey; } node.nest { size: 15px; fill-color: grey; } node.food { size: 15px; fill-color: green; }
				edge { arrow-shape: none; size: 5px; fill-mode: dyn-plain; fill-color: grey, green, orange, red; }
				sprite { fill-color: red; }
				sprite.back { fill-color: green; }
			""")		
	}

	/** Utility method to compute all the possible edges to explore from a given node.
	  * Only outgoing edges are selected. The returned array contains pairs of edge
	  * identifiers and their lengths. */
	protected def possibleEdges(node:Node):Array[(String,Double)] = {
		(node.getLeavingEdgeSet[Edge].map { edge => (edge.getId, edge.getNumber("ph")) }).toArray
	}		

	/** Utility method to get the pheromone level on an edge. */
	protected def getPh(edge:Edge):Double = edge.getNumber("ph")

	/** Utility method to store some pheromone quantity `ph` on an edge. */
	protected def updatePh(ph:Double, edge:Edge) {
		edge.setAttribute("ph", ph.asInstanceOf[AnyRef])
		edge.setAttribute("ui.label", "%.2f".format(ph))
		edge.setAttribute("ui.color", (ph/Ant.MaxPh).asInstanceOf[AnyRef])
	}

	/** Utility method to read the phromone level of an edge and add to it the given
	  * quantity `ph`. */
	protected def dropPheromone(ph:Double, edge:Edge) {
		var phe = ph + getPh(edge)
		if(phe > Ant.MaxPh) phe = Ant.MaxPh
		updatePh(phe, edge)
	}

	/** Create `count` ants on the nest node. */
	protected def initAnts(count:Int) {
		val edges = possibleEdges(nest)
		
		for(i <- 0 until count) {
			val id = "ant%d".format(i)

			val sprite = sprites.addSprite(id).asInstanceOf[AntSprite]

			sprite.controller = context.actorOf(Props[Ant], name=id)
			sprite.controller ! Ant.AtIntersection(edges)
		}
	}

	protected def nodePosition(node:Node, x:Int, y:Int) { node.setAttribute("xy", x.asInstanceOf[java.lang.Integer], y.asInstanceOf[java.lang.Integer]) }

	/** Apply evaporation on each edge. */
	protected def evaporate() {
		graph.getEachEdge.foreach { edge:Edge =>
			var ph = getPh(edge)
			ph *= Ant.Evaporation
			updatePh(ph, edge)
		}
	}

	/** Make the ant representation move, detect when ants reached intersections,
	  * the nest or the food and send messages to their actor to notify these events. */
	protected def runSprites() {
		sprites.iterator.foreach { sprite =>
			val antSprite = sprite.asInstanceOf[AntSprite]
			if(antSprite.run) {
				if(antSprite.goingBack) {
					if(antSprite.endPoint == nest) {
						antSprite.controller ! Ant.AtNest
					} else {
						antSprite.controller ! Ant.AtIntersection(null)
					}
				} else {
					if(antSprite.endPoint == food) {
						antSprite.controller ! Ant.AtFood
					} else {
						antSprite.controller ! Ant.AtIntersection(possibleEdges(antSprite.endPoint))
					}
				}
			}
		}
	}

	/** Behavior. */
	def receive() = {
		case ReceiveTimeout => {
			fromViewer.pump
			evaporate
			runSprites
		}
		case AntGoExploring(antId) => {
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
		case AntCrosses(antId, edgeId, ph) => {
			fromViewer.pump
			val length = GraphPosLengthUtils.edgeLength(graph, edgeId)
			val sprite = sprites.getSprite(antId).asInstanceOf[AntSprite]

			sprite.cross(edgeId, length)

			if(ph > 0)
				dropPheromone(ph, sprite.getAttachment.asInstanceOf[Edge])
		}
		case _ => {
			println("Graph: WTF ??")
		}
	}
}


// -- Ant Actor ---------------------------------------------------------------------------------------


object Ant {
	/** Maximum pheromon level on edges. */
	final val MaxPh = 3.0

	/** Pheromone conservation factor (not really evaporation, but as usual...). */
	final val Evaporation = 0.999

	/** Quantity of pheromone ants drop on edges. */
	final val phDrop = 0.1

	/** The ant reached an intersection. The data is a set of pairs of edge
	  * identifiers and lengths. */
	case class AtIntersection(edges:Array[(String,Double)])

	/** The ant reached a food source. */
	case class AtFood()

	/** The ant reached the nest. */
	case class AtNest()
}


class Ant extends Actor {
	import Ant._

	/** Edge currently traveled. */
	var edge:String = null

	/** Position on the edge between 0 and 1. */
	var position = 0.0

	/** Actual path of the ant from the nest to the food. */
	var memory = Stack[String]()

	/** If true instead of choosing the next edge, we unstack
	  * the memory to follow the path used at start. */
	var goingBack = false

	/** Random generator. */
	var random = scala.util.Random

	def receive() = {
		case AtIntersection(edges) => {
			var edge:String = null
			var ph:Double = 0.0

			if(goingBack) {
				edge = memory.pop
				ph = phDrop
			} else {
				edge = chooseNextEdge(edges)
				memory.push(edge)
			}
			
			sender ! GraphActor.AntCrosses(self.path.name, edge, ph)
		}
		case AtFood => {
			goingBack = true
			sender ! GraphActor.AntGoesBack(self.path.name)
		}
		case AtNest => {
			goingBack = false
			sender ! GraphActor.AntGoExploring(self.path.name)
		}
		case _ => {
			println("Ant: WTF ?!")
		}
	}

	def chooseNextEdgeRandom(edges:Array[(String,Double)]):String = { edges(random.nextInt(edges.length))._1 }
	
	def chooseNextEdge(edges:Array[(String,Double)]):String = {
//val buf = new StringBuilder()
//buf ++= "%s choose next { ".format(self.path.name)
		var sum = 0.0
		var rnd = random.nextDouble

		edges.foreach { edge => /*buf++="%s:%.2f ".format(edge._1,edge._2);*/ sum += edge._2 }

		if(sum <= 0) {
//buf++="} -> random"
//println(buf)
			chooseNextEdgeRandom(edges)
		} else {
//buf++="-> sum=%.2f rnd=%.2f (sum=%.2f) (%d) {".format(sum, rnd, sum*rnd, edges.length)
			sum *= rnd
			var tot = 0.0
			edges.find { edge => tot += edge._2; /*buf++=" %.2f".format(tot);*/ tot >= sum } match {
				case Some(x) => /*buf++=" } %s chooses %s".format(self.path.name, x._1); println(buf);*/ x._1
				case None    => /*buf++="WTF"; println(buf);*/ ""
			}
		}
	}
}