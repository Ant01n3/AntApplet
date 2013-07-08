Ant Applet
==========

A very simple program that tries to demonstrate how ants find the shortest path between a nest and one (or more) food sources.

The program uses Scala and the actor model to represent the ants. It uses GraphStream to represent an environment actor (a graph representing the possible paths for the ants). Then each ant is an actor that takes decision each time it encounters an intersection based on informations stored on the edges. Ants use a model inspired by the works of Jean-Louis Deneubourg and Marco Dorigo.

Installation and use
--------------------

To get it:

    git clone https://github.com/Ant01n3/AntApplet.git

(The up to date repository link is on the right on the GitHub page (HTTPS clone URL)).

To use it, install [SBT](http://www.scala-sbt.org/release/docs/Getting-Started/Setup.html), and launch it in the directory of this project (there are DEB, RPM, Gentoo packages as well as Homebrew and Macports packages). SBT will take care of all dependencies.

In the SBT console, to compile the project:

    compile

To run it:

    run

To package it as a standalone jar:

    proguard

You then can find the jar in ``target/scala-2.10/antsapplet_2.10-0.1.min.jar`` or something similar.

Launching the applet when using this jar:

    java -jar antsapplet_2.10-0.1.min.jar

How to change the graph
-----------------------

Several example graphs are provided in the ``src/main/resources`` directory (and packaged in the jar when using the ``proguard`` command).

You can load the examples by providing their name as the first argument on the command line prefixed by a slash:

    run "/TwoBridges.dgs"

Or with the jar:

    java -jar antsapplet_2.10-0.1.min.jar "/TwoBridges.dgs"

You can also load any graph prepared for this applet (see under) by giving its file name on the local file system:

    run "/path/to/my/graph.dgs"

How to make a graph for the applet
----------------------------------

The graphs are prepared in the [DGS](http://graphstream-project.org/doc/Advanced-Concepts/The-DGS-File-Format/) format.

Such a file must always start by a header:

    DGS004

Then you must create nodes and edges and position them. Edges must be oriented, leaving the nest. This avoids ants to make loops and allow them to go back to the nest. This is a trick, but in nature ants use other ways to find their paths than only pheromones.

Lets take a simple diamond shaped graph example with one nest, one food source and two paths to the food. First we add two nodes for
the nest and the food. Each node or edge creation command must be on its own line (blank lines are allowed and ``#`` delimit comments that run until the end of the line).

    an Nest nest ui.class="nest" ui.label="Nest" x=0 y=1
    an Food food ui.class="food" ui.label="Food" x=0 y=-1

The command ``an`` means "add node". The identifier of the node is ``Nest`` for example (identifiers are free). 

Then following the identifier are attributes. An attribute can be only a name or a name followed by an equal sign and a value. The attributes ``nest`` and ``food`` allow the applet to find the nest node (that must be unique, or else the first found one is used) and the food nodes (there can be several). These attribute do not need a value, they must be present.

The two attributes ``ui.class`` and ``ui.label`` allow to change the appearance of these special nodes and to make some text appear next to them. 

Finally the attributes ``x`` and ``y`` allow to position the nodes.

Now lets add the other nodes:

    an A x=-3 y=0
    an B x=1 y=0

We now can add edges:

    ae NestA Nest > A
    ae NestB Nest > B
    ae AFood A > Food
    ae BFood B > Food

Edges are added with the command ``ae`` (add edge). Then must follow the identifier of the edge (here ``NestA`` for example), and the identifiers of the two endpoint nodes. Between the two nodes identifiers you can use ``>`` ``<`` or nothing to indicate the direction (or undirected nature) of the edge. For this applet, edges must be oriented from the nest to the food source.

You can change the number of ants (by default 10) using the ``antCount`` attribute on the graph.

    cg antCount=6

The command ``cg`` (change graph) will add the attribute ``antCount`` with a number value. The other settings that can be changed are:

    cg phDrop=1          # Base quantity of pheromone dropped on edges when going back to nest, see gamma.
    cg alpha=1.5         # The relative pheromone importance.
    cg beta=1.0          # The relative edge length importance.
    cg gamma=3.0         # phDrop / pow(pathLengh,gamma), if 0, use only constant phDrop.
    cg minPh=0.1         # Minimum pheromone quantity on edges
    cg evaporation=0.995 # The edge pheromone 'conservation' at each step.

The values given above are the defaults. The evaporation rate is naturally tied to the number of ants.

Finally you can change the appearance of the graph using a style sheet:

    cg "ui.antialias"
    cg "ui.stylesheet"="node { fill-color: grey; } node.nest { size: 15px; fill-color: grey; } node.food { size: 15px; fill-color: green; } edge { arrow-shape: none; size: 5px; fill-mode: dyn-plain; fill-color: grey, green, orange, red; } sprite { fill-color: red; } sprite.back { fill-color: green; }"

You can find more details about this on the [stylesheet documentation page](http://graphstream-project.org/doc/Tutorials/GraphStream-CSS-Reference_1.0/).

The model
---------

The model for the ant behavior is inspired by works from [Jean-Louis Deneubourg](http://www.ulb.ac.be/sciences/use/deneubourg%20publications.html) and [Marco Dorigo](http://www.scholarpedia.org/article/Ant_colony_optimization). The idea is that ants uses a constrained environment modeled as a graph. They start from a nest node and travel freely on the graph until they reach a food node. They consume this food and go back to the nest usually following the same path and dropping pheromones on it only when coming back.

This model is inspired from nature and the works of Jean-Louis Deneubourg, but it departs from it on lots of points. First ants behavior is far more complex than that, they do not only use pheromones to choose their paths. Then the behavior depends on the species. 

However this model has been the basis for a lot works on optimization, and other inspiring topics. Therefore we use it here to mimic a real experience on ants.

Each ant is an independent agent that travels from the nest in order to forage food. When faced to a choice for continuing its path, it most of the time is influenced by the choices of its predecessors. This is done by the use of a kind of message dropped in the environment: pheromones. This kind of communication without contact is called [stigmergy](http://en.wikipedia.org/wiki/Stigmergy).

Naturally the more ants used a path before, the more there is pheromone and therefore the more an ant will be influenced to follow this path. This positive feedback loop allows to build paths toward food.

However pheromone tend to evaporate with time and must be regularly dropped on the path. If no more food is available at the end of the path, ants will not lay down pheromones and the path will disappear with time. This negative feedback mechanism allows ant to forget old non interesting paths.

Here we model ants that lay down pheromone only on their back path to return to the nest. In nature, ants do not work like this (a future version may allow to choose when to drop pheromones, and eventually to choose to go back by a different path). 

In our model, ants will choose the next edge to cross according to the following formula:

    w = p^alpha * (1-d)^beta

Where ``w`` is the weight of an edge, ``p`` is the pheromone on this edge, ``d`` is the length of this edge. Parameters ``alpha`` and ``beta`` allow to balance the relative importance of pheromones versus edge lengths. If ``beta`` is zero, the implementation uses the formula:

    w = p^alpha

When an ant encounters an intersection it considers each edge and determine a weight for these edges. Then using a biased fortune wheel, it chooses the next edge according to the weights. Note that this is a random process, biased by the edge weights, which means that an edge with a short length and a lot of pheromones has more chances to be chosen. However an ant can still take the "bad" edge. But this characteristic (which makes the algorithm non deterministic) is also a strength: this is what makes the ants able to find new better paths if the one they use actually is no more usable. This is also why the algorithm as a ``minPh`` value so that edges can always have a minimum pheromone value to let ants try it.

You can see that the ant is not only influenced by the pheromone present on the edge, it also follow a kind of greedy algorithm by preferring short edges than long ones if ``beta`` is not zero. This is a not a good strategy to find shortest paths alone, but coupled with pheromones it may improves things. You can however completely remove this behavior by setting ``beta`` at zero.

Ants drop pheromones on their path only when going back. The quantity of pheromone dropped on each edge is given by parameter ``phDrop`` and modified by parameter ``gamma``. If ``gamma`` is zero, 
the quantity dropped is the constant ``phDrop`` else we use the formula:

    drop = phDrop / l^gamma

Where ``l`` is the length of the path of the ant. Here again you can choose to have a constant, or to use the path length. This may change drastically the behavior of the ants.

Be careful that ``phDrop`` depends on several things:
- the size of the network,
- the quantity of ants,
- the gamma parameter.

Therefore the two most important parameters that you must change for each network is ``antCount`` and ``phDrop``.

The implementation
------------------

We use the [Akka](http://akka.io/) actor framework. The main idea is that there is an environment for the ants represented by a graph and managed by an actor.

Then each ant is also an actor that travels on the graph. The ants actor only role is to implement behavior, they take actions like : I arrived on a new intersection, what edge do I choose to cross ? Or I am at the food, what to do ?

The environment actor takes care of sending ants events like you are at an intersection, or you are on the food, and the ants answer with their choices. It also manages the representation of the ants and the GUI, and it is the environment that moves the little dots representing the ants, allowing it to know when an ant reached an intersection or the food. This is also the environment that implement the pheromone evaporation.

TODO talks of the various parts of the code.

Here is the behavior of an Ant:

```scala
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
    }
```

Here is the behavior of the graph actor:

```scala
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
    }
```

TODO talk of the messages exchanged by actors.

Implementation note: the actor model is implicitly mutli-threaded, but rest assured that there is not one thread per actor. Instead, Akka uses a thread pool. Most of the time the thread pool is as large as your number of cores. This model gracefully scales according to your resources. This implies that your environment graph actor, the GUI and the ants will be allowed to run in distinct threads if possible, but two ants can run on the same thread for example. Future agent-based simulation platforms will probably investigate actors.