#! /bin/sh

set -o errexit
set -o nounset
set -o xtrace

CI_ENV="${CI_ENV:-false}"
export MILL_VERSION="0.9.7"
TEST_ONLY="${1:-false}"

if [ ! -f mill ]; then
  curl -L https://github.com/com-lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION > mill && chmod +x mill
fi

./mill version

# The output directory for RTL code
mkdir -p ./rtl

# Generate IDEA config
# ./mill mill.scalalib.GenIdea/idea

if [ "$TEST_ONLY" = "false" ]; then
  # Run build and simulation
  ./mill rocev2.runMain rdma.RoCEv2

  # Check format and lint
  if [ "$CI_ENV" = "true" ]; then
    ./mill rocev2.checkFormat
    ./mill rocev2.fix --check
  else
    ./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
    ./mill rocev2.fix
  fi
fi

# mill test is not compatible with SpinalHDL, use testOnly instead
./mill rocev2.test.testOnly rdma.CoalesceAndNormalAndRetryNakHandlerTest
./mill rocev2.test.testOnly rdma.PktLenCheckTest
./mill rocev2.test.testOnly rdma.ReadAtomicRespVerifierAndFatalNakNotifierTest
./mill rocev2.test.testOnly rdma.ReadAtomicRespDmaReqInitiatorTest
./mill rocev2.test.testOnly rdma.RetryHandlerAndDmaReadInitTest
./mill rocev2.test.testOnly rdma.ReadRespGeneratorTest
./mill rocev2.test.testOnly rdma.ReqAddrValidatorTest
./mill rocev2.test.testOnly rdma.ReqCommCheckTest
./mill rocev2.test.testOnly rdma.RqSendWriteDmaReqInitiatorTest
./mill rocev2.test.testOnly rdma.RqReadDmaRespHandlerTest
./mill rocev2.test.testOnly rdma.SendReqGeneratorTest
./mill rocev2.test.testOnly rdma.WriteReqGeneratorTest
#./mill rocev2.test.testOnly rdma.SetSuite
./mill rocev2.test.testOnly rdma.ReqSplitterAndNakGenTest
./mill rocev2.test.testOnly rdma.ReadDmaReqInitiatorTest
./mill rocev2.test.testOnly rdma.RqReadAtomicDmaReqBuilderTest
./mill rocev2.test.testOnly rdma.DupReqHandlerAndReadAtomicRstCacheQueryTest
./mill rocev2.test.testOnly rdma.DupReadDmaReqBuilderTest
