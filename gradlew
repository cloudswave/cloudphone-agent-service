#!/bin/sh
# Gradle wrapper
DIRNAME="$(dirname "$0")"
CLASSPATH=$DIRNAME/gradle/wrapper/gradle-wrapper.jar
exec "$JAVA_HOME/bin/java" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
