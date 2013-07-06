AntApplet
=========

A very simple program that tries to demonstrate how ants find the shortest path between a nest and one (or more) food sources.

The program uses Scala and the actor model to represent the ants. It uses GraphStream to represent an environement actor (a graph representing the possible paths for the ants).

To compile, install [SBT](http://www.scala-sbt.org/) and launch it in the directory of this project.

To compile it:

    compile

To run it:

    run

To package it as a standalone jar:

    proguard

You then can find the jar in ``target/scala-2.10/antsapplet_2.10-0.1.min.jar`` or something similar.