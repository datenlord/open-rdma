#! /bin/sh

set -o errexit
set -o nounset
set -o xtrace

CI_ENV="${1:-false}"
MILL_VERSION="0.9.7"

if [ ! -f mill ]; then
  curl -L https://github.com/com-lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION > mill && chmod +x mill
fi

./mill version

# Generate IDEA config
# ./mill mill.scalalib.GenIdea/idea

# Check format and lint
if [ "$CI_ENV" = "true" ]; then
  ./mill rocev2.checkFormat
  ./mill rocev2.fix --check
else
  ./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
  ./mill rocev2.fix
fi

# Run build and simulation
./mill rocev2.runMain rdma.RoCEv2
# mill test is not compatible with SpinalHDL, use testOnly instead
# ./mill rocev2.test
./mill rocev2.test.testOnly rdma.SeqOutTest
