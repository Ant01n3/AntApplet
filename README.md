Ant Applet
==========

A very simple program that tries to demonstrate how ants find the shortest path between a nest and one (or more) food sources.

The program uses Scala and the actor model to represent the ants. It uses GraphStream to represent an environment actor (a graph representing the possible paths for the ants). Then each ant is an actor that takes decision each time it encounters an intersection based on informations stored on the edges.

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

Several example graphs are provided in the ```src/main/resources`` directory (and packaged in the jar when using the ``proguard`` command).

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
    "" 0 0

Then you must create nodes and edges and position them. Edges must be oriented, leaving the nest. This avoids ants to make loops and allow them to go back to the nest. This is a trick, but in nature ants use other ways to find their paths than only pheromones.

Lets take a simple diamond shaped graph example with one nest, one food source and two paths to the food. First we add two nodes for
the nest and the food.

    an Nest nest ui.class="nest" ui.label="Nest" x=0 y=1
    an Food food ui.class="food" ui.label="Food" x=0 y=-1

The command ``an`` means "add node". The identifier of the node is ``Nest`` for example (identifiers are free). 

Then following the identifier are attributes. An attribute can be only a name or a name followed by an equal sign and a value. The attributes ``nest`` and ``food`` allow the applet to find the nest node (that must be unique, or else the first found one is used) and the food nodes (there can be several). These attribute do not need a value, they must be present.

The two attributes ``ui.class`` and ``ui.label`` allow to change the appearance of these special nodes and to make some text appear next to them. 

Finally the attributes ``x`` and ``y`` allow to position the nodes.

Now lets add the other nodes:

    an A x=-1 y=0
    an B x=1 y=0

We now can add edges:

    ae NestA Nest > A
    ae NestB Nest > B
    ae AFood A > Food
    ae BFood B > Food

Edges are added with the command ``ae`` (add edge). Then must follow the identifier of the edge (here ``NestA`` for example), and the identifiers of the two endpoint nodes. Between the two nodes identifiers you can use ``>`` ``<`` or nothing to indicate the direction (or undirected nature) of the edge. For this applet, edges must be oriented from the nest to the food source.

You can change the number of ants (by default 10) using the ``antCount`` attribute on the graph.

    cg antCount=6

The command ``cg`` (change graph) will add the attribute ``antCount`` with a number value.

Finally you can change the appearance of the graph using a style sheet:

   cg "ui.antialias"
   cg "ui.stylesheet"="node { fill-color: grey; } node.nest { size: 15px; fill-color: grey; } node.food { size: 15px; fill-color: green; } edge { arrow-shape: none; size: 5px; fill-mode: dyn-plain; fill-color: grey, green, orange, red; } sprite { fill-color: red; } sprite.back { fill-color: green; }"

You can find more details about this on the [stylesheet documentation page](http://graphstream-project.org/doc/Tutorials/GraphStream-CSS-Reference_1.0/).

How does it works
-----------------

TODO

