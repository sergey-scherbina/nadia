#!/usr/bin/env sh
# Run the workspace OUTSIDE sbt: sbt reads stdin for its own commands, so a
# host that reads the console (terminal, chat, wire) must own the JVM itself.
#   ./run.sh terminal --fixture | ./run.sh chat --fixture | ./run.sh wire --fixture
# The classpath is exported once (`sbt "export Runtime/fullClasspath"`) and kept
# in target/classpath.txt; delete it after changing dependencies.
set -e
cd "$(dirname "$0")"
CP=target/classpath.txt
SBT=${SBT:-sbt}
if [ ! -s "$CP" ]; then
  mkdir -p target
  "$SBT" -batch "export Runtime/fullClasspath" 2>/dev/null | grep '^/' | tail -1 > "$CP"
  if [ ! -s "$CP" ]; then
    echo "run.sh: could not export the classpath with '$SBT' — is sbt on PATH (or SBT=/path/to/sbt)?" >&2
    rm -f "$CP"; exit 1
  fi
fi
exec java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$(cat "$CP")" nadia.ui.Main "$@"
