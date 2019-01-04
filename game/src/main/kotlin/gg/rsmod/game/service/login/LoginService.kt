package gg.rsmod.game.service.login

import gg.rsmod.game.Server
import gg.rsmod.game.model.World
import gg.rsmod.game.model.entity.Client
import gg.rsmod.game.protocol.GameHandler
import gg.rsmod.game.protocol.GameMessageEncoder
import gg.rsmod.game.protocol.PacketMetadataHelper
import gg.rsmod.game.service.GameService
import gg.rsmod.game.service.Service
import gg.rsmod.game.service.rsa.RsaService
import gg.rsmod.game.service.serializer.PlayerSerializerService
import gg.rsmod.game.system.GameSystem
import gg.rsmod.net.codec.game.GamePacketDecoder
import gg.rsmod.net.codec.game.GamePacketEncoder
import gg.rsmod.net.codec.login.LoginRequest
import gg.rsmod.util.ServerProperties
import gg.rsmod.util.concurrency.NamedThreadFactory
import gg.rsmod.util.io.IsaacRandom
import org.apache.logging.log4j.LogManager
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * A [Service] that is responsible for handling incoming login requests.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class LoginService : Service() {

    companion object {
        private val logger = LogManager.getLogger(LoginService::class.java)
    }

    /**
     * The [PlayerSerializerService] implementation that will be used to decode
     * and encode the player data.
     */
    lateinit var serializer: PlayerSerializerService

    /**
     * The [LoginServiceRequest] requests that will be handled by our workers.
     */
    val requests = LinkedBlockingQueue<LoginServiceRequest>()

    @Throws(RuntimeException::class)
    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        serializer = world.getService(PlayerSerializerService::class.java, true).get()

        val threadCount = serviceProperties.getOrDefault("thread-count", 3)
        val executorService = Executors.newFixedThreadPool(threadCount, NamedThreadFactory().setName("login-worker").build())
        for (i in 0 until threadCount) {
            executorService.execute(LoginWorker(this))
        }
    }

    override fun terminate(server: Server, world: World) {
    }

    fun addLoginRequest(world: World, request: LoginRequest) {
        val serviceRequest = LoginServiceRequest(world, request)
        requests.offer(serviceRequest)
    }

    fun successfulLogin(client: Client, encodeRandom: IsaacRandom, decodeRandom: IsaacRandom) {
        val gameSystem = GameSystem(channel = client.channel, client = client,
                service = client.world.getService(GameService::class.java, false).get())

        client.gameSystem = gameSystem
        client.channel.attr(GameHandler.SYSTEM_KEY).set(gameSystem)

        /**
         * NOTE(Tom): we should be able to use an parallel task to handle
         * the pipeline work and then schedule for the [client] to log in on the
         * next game cycle after completion. Should benchmark first.
         */
        val pipeline = client.channel.pipeline()
        val rsaEncryption = client.world.getService(RsaService::class.java, false).isPresent

        pipeline.remove("handshake_encoder")
        pipeline.remove("login_decoder")
        pipeline.remove("login_encoder")

        pipeline.addFirst("packet_encoder", GamePacketEncoder(encodeRandom, rsaEncryption))
        pipeline.addAfter("packet_encoder", "message_encoder", GameMessageEncoder(gameSystem.service.messageEncoders, gameSystem.service.messageStructures))

        pipeline.addBefore("handler", "packet_decoder", GamePacketDecoder(decodeRandom, rsaEncryption,
                PacketMetadataHelper(gameSystem.service.messageStructures)))

        client.login()
        client.channel.flush()
    }
}