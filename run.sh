#! /bin/sh

set -o errexit
set -o nounset
set -o xtrace

CI_ENV="${CI_ENV:-false}"
export MILL_VERSION="0.10.0"
TEST_ONLY="${1:-false}"

if [ ! -f mill ]; then
  curl -JLO https://github.com/com-lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION && mv $MILL_VERSION mill && chmod +x mill
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

#$MILL rocev2.test.testSim rdma.SetSuite
# mill test is not compatible with SpinalHDL, use testOnly instead

# Cache Test
$MILL rocev2.test.testSim rdma.FifoTest
$MILL rocev2.test.testSim rdma.PdAddrCacheTest
$MILL rocev2.test.testSim rdma.ReadAtomicRstCacheTest
$MILL rocev2.test.testSim rdma.WorkReqCacheTest

# SQ Test
$MILL rocev2.test.testSim rdma.SendReqGeneratorTest
$MILL rocev2.test.testSim rdma.WriteReqGeneratorTest
$MILL rocev2.test.testSim rdma.WorkReqValidatorTest
$MILL rocev2.test.testSim rdma.WorkReqCacheAndOutPsnRangeHandlerTest
$MILL rocev2.test.testSim rdma.SqOutTest # Slow test

# Retry Handler Test
$MILL rocev2.test.testSim rdma.RetryHandlerTest

# Response Handler Test
$MILL rocev2.test.testSim rdma.CoalesceAndNormalAndRetryNakHandlerTest
$MILL rocev2.test.testSim rdma.ReadRespLenCheckTest
$MILL rocev2.test.testSim rdma.ReadAtomicRespVerifierAndFatalNakNotifierTest
$MILL rocev2.test.testSim rdma.ReadAtomicRespDmaReqInitiatorTest
$MILL rocev2.test.testSim rdma.WorkCompGenTest

# RQ Test
$MILL rocev2.test.testSim rdma.ReqCommCheckTest
$MILL rocev2.test.testSim rdma.ReqRnrCheckTest
$MILL rocev2.test.testSim rdma.DupReqHandlerAndReadAtomicRstCacheQueryTest
$MILL rocev2.test.testSim rdma.DupReadDmaReqBuilderTest
$MILL rocev2.test.testSim rdma.ReqAddrInfoExtractorTest
$MILL rocev2.test.testSim rdma.ReqAddrValidatorTest
$MILL rocev2.test.testSim rdma.ReqPktLenCheckTest
$MILL rocev2.test.testSim rdma.ReqSplitterAndNakGenTest
$MILL rocev2.test.testSim rdma.RqSendWriteDmaReqInitiatorTest
$MILL rocev2.test.testSim rdma.RqReadAtomicDmaReqBuilderTest
$MILL rocev2.test.testSim rdma.ReadDmaReqInitiatorTest
# Slow test
$MILL rocev2.test.testSim rdma.SendWriteRespGeneratorTest
$MILL rocev2.test.testSim rdma.RqSendWriteWorkCompGeneratorTest

$MILL rocev2.test.testSim rdma.RqReadDmaRespHandlerTest
$MILL rocev2.test.testSim rdma.ReadRespGeneratorTest
$MILL rocev2.test.testSim rdma.RqOutTest
