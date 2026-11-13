#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
task_classes=$(mktemp -d)
trap 'rm -rf "$task_classes"' EXIT
javac -encoding UTF-8 -d "$task_classes" app/src/main/java/dev/myiftt/forwarder/ForwardRules.java tools/CoreSelfTest.java
java -ea -cp "$task_classes" dev.myiftt.forwarder.CoreSelfTest
