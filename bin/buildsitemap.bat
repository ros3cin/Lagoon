@echo off

rem Windows batch script for running Lagoon

rem SET LAGOON_HOME=D:\lagoon

java -classpath %CLASSPATH%;%LAGOON_HOME%\lagoon.jar;%LAGOON_HOME%\xmlutil.jar nu.staldal.lagoon.BuildSitemap %1 %2 %3 %4 %5 %6 %7 %8 %9
