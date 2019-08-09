package io.emeraldpay.dshackle.upstream

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import io.emeraldpay.api.proto.BlockchainGrpc
import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.Common
import io.emeraldpay.dshackle.test.EthereumApiMock
import io.emeraldpay.dshackle.test.MockServer
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.grpc.Chain
import io.grpc.stub.StreamObserver
import io.infinitape.etherjar.domain.BlockHash
import io.infinitape.etherjar.rpc.RpcClient
import io.infinitape.etherjar.rpc.json.BlockJson
import org.apache.commons.codec.binary.Hex
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.CompletableFuture

class GrpcUpstreamSpec extends Specification {

    MockServer mockServer = new MockServer()
    ObjectMapper objectMapper = TestingCommons.objectMapper()
    def ethereumTargets = new EthereumTargets(objectMapper, Chain.ETHEREUM)

    def "Subscribe to head"() {
        setup:
        def callData = [:]
        def chain = Chain.ETHEREUM
        def api = TestingCommons.api(Stub(RpcClient), Stub(Upstream))
        def block1 = new BlockJson().with {
            it.number = 650246
            it.hash = BlockHash.from("0x50d26e119968e791970d84a7bf5d0ec474d3ec2ef85d5ec8915210ac6bc09ad7")
            it.totalDifficulty = new BigInteger("35bbde5595de6456", 16)
            return it
        }
        api.answer("eth_getBlockByHash", [block1.hash.toHex(), false], block1)
        def client = mockServer.clientForServer(new BlockchainGrpc.BlockchainImplBase() {
            @Override
            void nativeCall(BlockchainOuterClass.NativeCallRequest request, StreamObserver<BlockchainOuterClass.NativeCallReplyItem> responseObserver) {
                api.nativeCall(request, responseObserver)
            }

            @Override
            void subscribeHead(Common.Chain request, StreamObserver<BlockchainOuterClass.ChainHead> responseObserver) {
                callData.chain = request.getTypeValue()
                responseObserver.onNext(
                        BlockchainOuterClass.ChainHead.newBuilder()
                                .setBlockId(block1.hash.toHex().substring(2))
                                .setHeight(block1.number)
                                .setWeight(ByteString.copyFrom(block1.totalDifficulty.toByteArray()))
                                .build()
                )
            }
        })
        def upstream = new GrpcUpstream(chain, client, objectMapper, ethereumTargets)
        upstream.setLag(0)
        when:
        upstream.connect()
        def h = upstream.head.head.block(Duration.ofSeconds(1))
        then:
        callData.chain == Chain.ETHEREUM.id
        upstream.status == UpstreamAvailability.OK
        h.hash == BlockHash.from("0x50d26e119968e791970d84a7bf5d0ec474d3ec2ef85d5ec8915210ac6bc09ad7")
    }

    def "Follows difficulty, ignores less difficult"() {
        setup:
        def callData = [:]
        def finished = new CompletableFuture<Boolean>()
        def chain = Chain.ETHEREUM
        def api = TestingCommons.api(Stub(RpcClient), Stub(Upstream))
        def block1 = new BlockJson().with {
            it.number = 650246
            it.hash = BlockHash.from("0x50d26e119968e791970d84a7bf5d0ec474d3ec2ef85d5ec8915210ac6bc09ad7")
            it.totalDifficulty = new BigInteger("35bbde5595de6456", 16)
            return it
        }
        def block2 = new BlockJson().with {
            it.number = 650247
            it.hash = BlockHash.from("0x3ec2ebf5d0ec474d0ac6bc50d2770d8409ad76e119968e7919f85d5ec891521a")
            it.totalDifficulty = new BigInteger("35bbde5595de6455", 16)
            return it
        }
        api.answer("eth_getBlockByHash", [block1.hash.toHex(), false], block1)
        api.answer("eth_getBlockByHash", [block2.hash.toHex(), false], block2)
        def client = mockServer.clientForServer(new BlockchainGrpc.BlockchainImplBase() {
            @Override
            void nativeCall(BlockchainOuterClass.NativeCallRequest request, StreamObserver<BlockchainOuterClass.NativeCallReplyItem> responseObserver) {
                api.nativeCall(request, responseObserver)
            }

            @Override
            void subscribeHead(Common.Chain request, StreamObserver<BlockchainOuterClass.ChainHead> responseObserver) {
                responseObserver.onNext(
                        BlockchainOuterClass.ChainHead.newBuilder()
                                .setBlockId(block1.hash.toHex().substring(2))
                                .setHeight(block1.number)
                                .setWeight(ByteString.copyFrom(block1.totalDifficulty.toByteArray()))
                                .build()
                )
                responseObserver.onNext(
                        BlockchainOuterClass.ChainHead.newBuilder()
                                .setBlockId(block2.hash.toHex().substring(2))
                                .setHeight(block2.number)
                                .setWeight(ByteString.copyFrom(block2.totalDifficulty.toByteArray()))
                                .build()
                )
                finished.complete(true)
            }
        })
        def upstream = new GrpcUpstream(chain, client, objectMapper, ethereumTargets)
        upstream.setLag(0)
        when:
        upstream.connect()
        finished.get()
        def h = upstream.head.head.block(Duration.ofSeconds(1))
        then:
        upstream.status == UpstreamAvailability.OK
        h.hash == BlockHash.from("0x50d26e119968e791970d84a7bf5d0ec474d3ec2ef85d5ec8915210ac6bc09ad7")
        h.number == 650246
    }

    def "Follows difficulty"() {
        setup:
        def callData = [:]
        def finished = new CompletableFuture<Boolean>()
        def chain = Chain.ETHEREUM
        def api = TestingCommons.api(Stub(RpcClient), Stub(Upstream))
        def block1 = new BlockJson().with {
            it.number = 650246
            it.hash = BlockHash.from("0x50d26e119968e791970d84a7bf5d0ec474d3ec2ef85d5ec8915210ac6bc09ad7")
            it.totalDifficulty = new BigInteger("35bbde5595de6456", 16)
            return it
        }
        def block2 = new BlockJson().with {
            it.number = 650247
            it.hash = BlockHash.from("0x3ec2ebf5d0ec474d0ac6bc50d2770d8409ad76e119968e7919f85d5ec891521a")
            it.totalDifficulty = new BigInteger("35bbde5595de6457", 16)
            return it
        }
        api.answer("eth_getBlockByHash", [block1.hash.toHex(), false], block1)
        api.answer("eth_getBlockByHash", [block2.hash.toHex(), false], block2)
        def client = mockServer.clientForServer(new BlockchainGrpc.BlockchainImplBase() {
            @Override
            void nativeCall(BlockchainOuterClass.NativeCallRequest request, StreamObserver<BlockchainOuterClass.NativeCallReplyItem> responseObserver) {
                api.nativeCall(request, responseObserver)
            }

            @Override
            void subscribeHead(Common.Chain request, StreamObserver<BlockchainOuterClass.ChainHead> responseObserver) {
                responseObserver.onNext(
                        BlockchainOuterClass.ChainHead.newBuilder()
                                .setBlockId(block1.hash.toHex().substring(2))
                                .setHeight(block1.number)
                                .setWeight(ByteString.copyFrom(block1.totalDifficulty.toByteArray()))
                                .build()
                )
                responseObserver.onNext(
                        BlockchainOuterClass.ChainHead.newBuilder()
                                .setBlockId(block2.hash.toHex().substring(2))
                                .setHeight(block2.number)
                                .setWeight(ByteString.copyFrom(block2.totalDifficulty.toByteArray()))
                                .build()
                )
                finished.complete(true)
            }
        })
        def upstream = new GrpcUpstream(chain, client, objectMapper, ethereumTargets)
        upstream.setLag(0)
        when:
        upstream.connect()
        finished.get()
        def h = upstream.head.head.block(Duration.ofSeconds(1))
        then:
        upstream.status == UpstreamAvailability.OK
        h.hash == BlockHash.from("0x3ec2ebf5d0ec474d0ac6bc50d2770d8409ad76e119968e7919f85d5ec891521a")
        h.number == 650247
    }
}
