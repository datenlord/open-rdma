#! /bin/sh

set -o errexit
set -o nounset
set -o xtrace

MILL_VERSION=0.9.9

if [ ! -f mill ]; then
  curl -L https://github.com/com-lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION > mill && chmod +x mill
fi

./mill version

# Check format and lint
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
# ./mill workshop.checkFormat
./mill rocev2.fix
# ./mill workshop.fix --check

# Run build and simulation
./mill rocev2.runMain rdma.RoCEv2
./mill rocev2.test
