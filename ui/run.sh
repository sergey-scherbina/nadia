#!/usr/bin/env sh
# Run the workspace OUTSIDE sbt: sbt reads stdin for its own commands, so a
# host that reads the console (terminal, chat, wire) must own the JVM itself.
#   ./run.sh terminal --fixture | ./run.sh chat --fixture | ./run.sh wire --fixture
# The classpath is exported once (`sbt "export Runtime/fullClasspath"`) and kept
# in target/classpath.txt; delete it after changing dependencies.
set -e
cd "$(dirname "$0")"
CP=target/classpath.txt
if [ ! -s "$CP" ]; then
  mkdir -p target
  sbt -batch "export Runtime/fullClasspath" 2>/dev/null | grep '^/' | tail -1 > "$CP"
fi
exec java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$(cat "$CP")" nadia.ui.Main "$@"
