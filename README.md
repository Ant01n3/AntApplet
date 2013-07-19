Ant Applet
==========

A very simple program that tries to demonstrate how ants find the shortest path between a nest and one (or more) food sources.

The program uses [Scala](http://www.scala-lang.org/) and the [actor model](http://en.wikipedia.org/wiki/Actor_model) to represent the ants. It uses [GraphStream](http://graphstream-project.org/) to represent an environment actor (a graph representing the possible paths for the ants). Then each ant is an actor that takes orientation decisions each time it encounters an intersection based on informations stored on the edges. Ants use a model inspired by the works of Jean-Louis Deneubourg and Marco Dorigo.

This readme contains the following information:

* [Installation and use](#installation-and-use)
* [Changing the ant environment graph](#how-to-change-the-graph)
* [Creating your own ant environment graphs](#how-to-make-a-graph-for-the-applet)
* [The ant model used](#the-model)
* [The implementation](#the-implementation)

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

To create the API documentation:

    doc 

You can then find the documentation in ``target/scala-2.10/api``.

How to change the graph
-----------------------

Several example environment graphs are provided in the ``src/main/resources`` directory (and packaged in the jar when using the ``proguard`` command).

You can load the examples by providing their name as the first argument on the command line prefixed by a slash:

    run "/FourBridges.dgs"

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
    cg speed=0.1         # Speed of ants.
    cg noMemory          # If present the ants have no memory of their exploration path, else the ants will go back using exactly their exploration path.

The values given above are the defaults. The evaporation rate is naturally tied to the number of ants.

Finally you can change the appearance of the graph using a style sheet:

    cg "ui.antialias"
    cg "ui.stylesheet"="node { fill-color: grey; } node.nest { size: 15px; fill-color: grey; } node.food { size: 15px; fill-color: green; } edge { arrow-shape: none; size: 5px; fill-mode: dyn-plain; fill-color: grey, green, orange, red; } sprite { fill-color: red; } sprite.back { fill-color: green; }"

You can find more details about this on the [stylesheet documentation page](http://graphstream-project.org/doc/Tutorials/GraphStream-CSS-Reference_1.0/).

The model
---------

The model for the ant behavior is inspired by works from [Jean-Louis Deneubourg](http://www.ulb.ac.be/sciences/use/deneubourg%20publications.html) and [Marco Dorigo](http://www.scholarpedia.org/article/Ant_colony_optimization). The idea is that ants uses a constrained environment modeled as a graph. They start from a nest node and travel freely on the graph until they reach a food node. They consume this food and go back to the nest (following the same path or not) and dropping pheromones on it only when coming back.

This model is inspired from nature and the works of Jean-Louis Deneubourg, but it departs from it on lots of points. First ants behavior is far more complex than that, they do not only use pheromones to choose their paths. Then the behavior depends on the species. 

However this model has been the basis for a lot works on optimization, and other inspiring topics. Therefore we use it here to mimic a real experience on ants.

Each ant is an independent agent that travels from the nest in order to forage food. When faced to a choice for continuing its path, it most of the time is influenced by the choices of its predecessors. This is done by the use of a kind of message dropped in the environment: pheromones. This kind of communication without contact is called [stigmergy](http://en.wikipedia.org/wiki/Stigmergy).

Naturally the more ants used a path before, the more there is pheromone and therefore the more an ant will be influenced to follow this path. This positive feedback loop allows to build paths toward food.

However pheromone tend to evaporate with time and must be regularly dropped on the path. If no more food is available at the end of the path, ants will not lay down pheromones and the path will disappear with time. This negative feedback mechanism allows ant to forget old non interesting paths.

As ants travel on edges at a constant speed, ants using shorter paths
will reach food faster, and if they use the same path to go back to
nest will also reach the nest faster. Therefore they will have a better chance to drop pheromones before others on the correct path, and therefore influence more other ants to use this path. This is one
of the critical feature of the algorithm allowing to select the shortest paths.

Here we model ants that lay down pheromone only on their back path to return to the nest. In nature, ants do not work like this (a future version may allow to choose when to drop pheromones). 

In our model, ants will choose the next edge to cross according to the following formula:

    w = p^alpha * (1/d)^beta

![equation](http://latex.codecogs.com/gif.latex?w%3Dp%5E%7B%5Calpha%7D*%281%2Fd%29%5E%7B%5Cbeta%7D)

Where ``w`` is the weight of an edge, ``p`` is the pheromone on this edge, ``d`` is the length of this edge. Parameters ``alpha`` and ``beta`` allow to balance the relative importance of pheromones versus edge lengths. If ``beta`` is zero, the implementation uses the formula:

    w = p^alpha

![equation](http://latex.codecogs.com/gif.latex?w%3Dp%5E%7B%5Calpha%7D)

When an ant encounters an intersection it considers each edge and determines a weight for these edges. Then using a biased fortune wheel, it chooses the next edge according to the weights. Note that this is a random process, biased by the edge weights, which means that an edge with a short length and a lot of pheromones has more chances to be chosen. However an ant can still take the "bad" edge. But this characteristic (which makes the algorithm non deterministic) is also a strength: this is what makes the ants able to find new better paths if the one they use actually is no more usable. This is also why the algorithm as a ``minPh`` value so that edges can always have a minimum pheromone value to let ants try it.

You can see that the ant is not only influenced by the pheromone present on the edge, it also follows a kind of greedy algorithm by preferring short edges than long ones if ``beta`` is not zero. This is a not a good strategy to find shortest paths alone, but coupled with pheromones it may improves things. You can however completely remove this behavior by setting ``beta`` at zero.

Ants drop pheromones on their path only when going back. The quantity of pheromone dropped on each edge is given by parameter ``phDrop`` and modified by parameter ``gamma``. If ``gamma`` is zero, 
the quantity dropped is the constant ``phDrop`` else we use the formula:

    drop = phDrop / l^gamma

![equation](http://latex.codecogs.com/gif.latex?drop%3D%5Cfrac%7BphDrop%7D%7Bl%5E%7B%5Cgamma%7D%7D)

Where ``l`` is the length of the path of the ant toward food. Here again you can choose to have a constant, or to use the path length. This may change drastically the behavior of the ants.

Be careful that ``phDrop`` depends on several things:
- the size of the network,
- the quantity of ants,
- the gamma parameter.

Therefore the two most important parameters that you must change for each network is ``antCount`` and ``phDrop``.

There is another parameter nammed ``noMemory``. This parameter allows to choose if the ant, once it has found food, will go back to the nest using the same path it used to find food or using another path. When using memory, when returning to the nest, ants will use their memory to travel back to the nest using edges in reverse order in their memory. They do not do any choice. When not using memory, ants use the same formula as when searching for food to evaluate which edge to choose. Most of the time, when you use ``noMemory`` you also want to set ``gamma`` to zero, since the ant will not necessarily use the same path as during exploration when returning to the nest, it is not interesting to drop a quantity of pheromone that depends on the path length.

The implementation
------------------

We use the [Akka](http://akka.io/) actor framework. The main idea is that there is an environment for the ants represented by a graph and managed by an actor.

Then each ant is also an actor that travels on the graph. The ants actor only role is to implement behavior, they take actions like : I arrived on a new intersection, what edge do I choose to cross ? Or I am at the food, what to do ?

The environment actor takes care of sending ants events like you are at an intersection, or you are on the food, and the ants answer with their choices. It also manages the representation of the ants and the GUI, and it is the environment that moves the little dots representing the ants, allowing it to know when an ant reached an intersection or the food. This is also the environment that implement the pheromone evaporation.

The components of this applet:

* The ``Environment`` actor that manage the physical environment for the ants.
* The ``Ant``s actors implementing the behavior of the ants.
* The ``AntSprite``s objects that represent ants on screen, managed by the environment, moving at constant speed and allowing the environment to know when an ant really reached an intersection.
* The ``Pheromone``s, objects stored on each edge and storing the pheromone value for each type of ant.

As often with actors, the best starting point to read the program is their respective ``receive`` method. These method implement the behavior of the actors.

The environment has a special ``ReceiveTimeout`` behavior that is called automatically by the system every 10 milliseconds. This clock behavior allows to implement the moving of ant representations, the pheromone evaporation, etc. And it ensures that all remains synchronous (no ant goes faster than another).

Then the environment will "talk" with the various ant actors by exchanging messages. First the environment will create an ant actor at each timeout if the required total number of ants is not reached. This allows to launch ants one by one. Just after creating an ant, the environment sends it two messages. First a ``AntType`` that allows the ant to know its species (by default there is only one species). Then a ``AtIntersection`` message. These messages contain information (pheromone values, lengths, identifiers) on all the edges that leave a node. This first message always refer to the nest node.

When an ant receives a ``AtIntersection`` message it can do two things. Either the ant is in "exploration" mode and it will have to choose which edge to follow according to the model explained above. Or it is in "returning" mode (it has found a food node) and it will follow the edges it used to come to the food in reverse order until it reaches the nest if it has memory, if not it will use the same mechanism as when exploring. In these two cases, the ant will send a ``AntCrosses`` message to the environment telling which edge it follows and informations like the quantity of pheromone to drop on it for example.

The environment will maintain a "sprite" representing the ant that it will animate to know when it reached a node in the graph. When this happens, either the ant is in "exploration mode" or in "returning" mode. In the two cases, the environment will most of the time send a ``AtIntersection`` message to the ant. In the first mode the message contains informations on all the possible edges that the ant may cross next. In the second mode it will contain no data (since the ant always follow its path in reverse order to come back to the nest). However, instead of the ``AtIntersection`` message, the environment may also send ``AtFood`` to signal that the ant is not on an intersection but reached a food node or ``AtNest``. This also depends on the mode of the ant (exploring, returning). It can also send a ``Lost`` message is there is no possible intersection, when the ant reached a dead-end (remember that the graph is directed).

We already know what an ant does when it receives a ``AtIntersection`` message. When it receives a ``AtFood`` message it switch from "exploration" mode to "returning" mode and send a ``AntGoesBack`` message to the environment. Then, each time it will receive a ``AtIntersection`` message it will ask to cross the edges of its path to food in reverse order until it receives a ``AtNest`` message, where it switch back from "returning" to "exploring" mode by sending a ``AntGoesExploring`` message to the environment.

When the environment receives a ``AntGoesBack`` message, it sends back a ``AtIntersection`` message. We have seen that the ant will respond by a ``AntCross`` message with edges of its path toward the food in reverse order. When the environment receives a ``AntGoesExploring`` message, it sends back a ``AtIntersection`` message with the possibles leaving edges of the nest node, just like for a new ant.

Here is the behavior of the environment:

```scala
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
        case AntGoesExploring(antId) ⇒ {
            val antSprite = antRepresentation(antId)
            antSprite.goBack(false)
            antSprite.actor ! Ant.AtIntersection(possibleEdgesForward(nest))
        }
        case AntGoesBack(antId) ⇒ {
            val antSprite = antRepresentation(antId)
            antSprite.goBack(true)
            antSprite.actor ! Ant.AtIntersection(possibleEdgesBackward(antSprite.lastFood))
        }
        case AntCrosses(antId, edgeId, antType, drop) ⇒ {
            fromViewer.pump
            val length = GraphPosLengthUtils.edgeLength(graph, edgeId)
            val sprite = antRepresentation(antId)

            sprite.cross(edgeId, length)

            if(drop > 0)
                dropPh(antType, drop, sprite.getAttachment.asInstanceOf[Edge])
        }
    }
```

Here is the behavior of an Ant:

```scala
    def receive() = {
        case AntType(theAntType) ⇒ {
            antType = theAntType
        }
        case AtIntersection(edges) ⇒ {
            var edge:String = null
            var drop:Double = 0.0

            if(goingBack) {
                edge = chooseNextEdgeBackward(edges)
                drop = if(gamma <= 0) phDrop else phDrop / pow(pathLength, gamma)
            } else {
                val chosen = chooseNextEdgeForward(edges)
                edge       = chosen.id
                memorize(chosen)
            }
            
            sender ! Environment.AntCrosses(id, edge, antType, drop)
        }
        case AtFood(nodeFoodType) ⇒ {
            if(antType == nodeFoodType) {
                goingBack = true
                sender ! Environment.AntGoesBack(id)
            } else {
                resetMemory
                sender ! Environment.AntGoesExploring(id)
            }
        }
        case AtNest ⇒ {
            resetMemory
            sender ! Environment.AntGoesExploring(id)
        }
        case Lost ⇒ {
            resetMemory
            sender ! Environment.AntGoesExploring(id)
        }
    }
```

All the protocol between the environment and the ants is explained above. All the model however is expressed in the way the ants choose the next edge to cross when exploring, and on the quantity of pheromone they drop on edges when returning. You can have a look at the ``Ant.chooseNextEdgeForward()`` and ``Ant.chooseNextEdgeBackward()`` methods and at the ``AtIntersection`` message handling in ``Ant.receive()`` to see how the model is implemented. You can also have a look at the ``Environment.moveAntsRepresentations()`` to see how ants representations are moved, intersection detected, and messages sent to the ant actors.

Implementation note 1: this implementation can be seen as an academic treatment of the problem. We do not seek to be the fastest way to do it or the more complete with lots of parameters. Instead we try to be as simple as possible, and allowing experimentation. This is also a way to experiment on the actor model, which is remarkably suited for such "simulation" problems.

Implementation note 2: the actor model is implicitly mutli-threaded, but rest assured that there is not one thread per actor. Instead, Akka uses a thread pool. Most of the time the thread pool is as large as your number of cores. This model gracefully scales according to your resources. This implies that your environment actor, the GUI and the ant actors will be allowed to run in distinct threads if possible, but two ants can run on the same thread for example.