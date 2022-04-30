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

MILL="./mill --no-server"
$MILL version

# The output directory for RTL code
mkdir -p ./rtl

# Generate IDEA config
# $MILL mill.scalalib.GenIdea/idea

if [ "$TEST_ONLY" = "false" ]; then
  # Run build and simulation
  $MILL rocev2.runMain rdma.RoCEv2

  # Check format and lint
  if [ "$CI_ENV" = "true" ]; then
    $MILL rocev2.checkFormat
    $MILL rocev2.fix --check
  else
    $MILL mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
    $MILL rocev2.fix
  fi
fi

#$MILL rocev2.test.testOnly rdma.SetSuite
# mill test is not compatible with SpinalHDL, use testOnly instead

# Cache Test
$MILL rocev2.test.testOnly rdma.ReadAtomicRstCacheTest
$MILL rocev2.test.testOnly rdma.WorkReqCacheTest

# SQ Test
$MILL rocev2.test.testOnly rdma.SendReqGeneratorTest
$MILL rocev2.test.testOnly rdma.WriteReqGeneratorTest
$MILL rocev2.test.testOnly rdma.WorkReqValidatorTest
$MILL rocev2.test.testOnly rdma.WorkReqCacheAndOutPsnRangeHandlerTest
$MILL rocev2.test.testOnly rdma.SqOutTest # Slow test

# Retry Handler Test
$MILL rocev2.test.testOnly rdma.RetryHandlerTest

# Response Handler Test
$MILL rocev2.test.testOnly rdma.CoalesceAndNormalAndRetryNakHandlerTest
$MILL rocev2.test.testOnly rdma.ReadRespLenCheckTest
$MILL rocev2.test.testOnly rdma.ReadAtomicRespVerifierAndFatalNakNotifierTest
$MILL rocev2.test.testOnly rdma.ReadAtomicRespDmaReqInitiatorTest

# RQ Test
$MILL rocev2.test.testOnly rdma.ReqCommCheckTest
$MILL rocev2.test.testOnly rdma.ReqRnrCheckTest
$MILL rocev2.test.testOnly rdma.DupReqHandlerAndReadAtomicRstCacheQueryTest
$MILL rocev2.test.testOnly rdma.DupReadDmaReqBuilderTest
$MILL rocev2.test.testOnly rdma.ReqAddrInfoExtractorTest
$MILL rocev2.test.testOnly rdma.ReqAddrValidatorTest
$MILL rocev2.test.testOnly rdma.ReqPktLenCheckTest
$MILL rocev2.test.testOnly rdma.ReqSplitterAndNakGenTest
$MILL rocev2.test.testOnly rdma.RqSendWriteDmaReqInitiatorTest
$MILL rocev2.test.testOnly rdma.RqReadAtomicDmaReqBuilderTest
$MILL rocev2.test.testOnly rdma.ReadDmaReqInitiatorTest
# Slow test
$MILL rocev2.test.testOnly rdma.SendWriteRespGeneratorTest
$MILL rocev2.test.testOnly rdma.RqSendWriteWorkCompGeneratorTest

$MILL rocev2.test.testOnly rdma.RqReadDmaRespHandlerTest
$MILL rocev2.test.testOnly rdma.ReadRespGeneratorTest
$MILL rocev2.test.testOnly rdma.RqOutTest
