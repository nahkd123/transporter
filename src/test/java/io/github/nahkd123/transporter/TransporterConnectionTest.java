package io.github.nahkd123.transporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import io.github.nahkd123.transporter.serialize.BufferCodec;

class TransporterConnectionTest {
	static record PingPacket(int message) {
		static final BufferCodec<PingPacket> CODEC = BufferCodec.tupleOf(
			BufferCodec.VUINT, PingPacket::message,
			PingPacket::new);
	}

	static record PongPacket(int message) {
		static final BufferCodec<PongPacket> CODEC = BufferCodec.tupleOf(
			BufferCodec.VUINT, PongPacket::message,
			PongPacket::new);
	}

	static class MyConnection extends TransporterConnection {
		ByteChannel channel;
		CompletableFuture<Void> closeTask = new CompletableFuture<>();

		MyConnection(ByteChannel channel) {
			this.channel = channel;
			registerPacket(0x00, PingPacket.class, PingPacket.CODEC);
			registerPacket(0x01, PongPacket.class, PongPacket.CODEC);
		}

		static CompletableFuture<CompletableFuture<MyConnection>> createServer(Path socketPath) throws IOException {
			Files.deleteIfExists(socketPath);
			UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
			CompletableFuture<CompletableFuture<MyConnection>> listenTask = new CompletableFuture<>();
			CompletableFuture<MyConnection> connectionTask = new CompletableFuture<>();

			Thread.startVirtualThread(() -> {
				try {
					ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
					listener.bind(addr);
					listenTask.complete(connectionTask);

					SocketChannel channel = listener.accept();
					channel.configureBlocking(false);

					try (MyConnection c = new MyConnection(channel)) {
						connectionTask.complete(c);

						while (!c.isClosed()) {
							boolean b = c.channelRead(channel);
							b |= c.channelWrite(channel);
							if (!b) LockSupport.parkNanos(1000000L);
						}
					}
				} catch (IOException e) {
					listenTask.completeExceptionally(e);
					connectionTask.completeExceptionally(e);
				}
			});

			return listenTask;
		}

		static CompletableFuture<MyConnection> createClient(Path socketPath) throws IOException {
			UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
			CompletableFuture<MyConnection> task = new CompletableFuture<>();

			Thread.startVirtualThread(() -> {
				try {
					SocketChannel channel = SocketChannel.open(addr);
					channel.configureBlocking(false);

					try (MyConnection c = new MyConnection(channel)) {
						task.complete(c);

						while (!c.isClosed()) {
							boolean b = c.channelRead(channel);
							b |= c.channelWrite(channel);
							if (!b) LockSupport.parkNanos(1000000L);
						}
					}
				} catch (IOException e) {
					task.completeExceptionally(e);
				}
			});

			return task;
		}

		@Override
		protected void onClose(boolean remote, Throwable error) {
			try {
				closeTask.complete(null);
				if (!remote) channel.close();
			} catch (Exception e) {
				fail(e);
			}
		}

		@Override
		protected void onRequest(TransporterConnection.Request request) throws Throwable {
			switch (request.packet()) {
			case PingPacket(int message):
				request.responseSuccess(new PongPacket(message));
				break;
			default:
				fail();
			}
		}

		@Override
		protected ByteBuffer createConnectionBuffer() {
			return ByteBuffer.allocate(256);
		}

		public int ping(int message) {
			return this.<PongPacket>queueRequest(new PingPacket(message)).join().message;
		}
	}

	@Test
	void test() throws IOException {
		Path socketPath = Path.of(getClass().getName());
		CompletableFuture<MyConnection> serverTask = MyConnection.createServer(socketPath).join();
		MyConnection client = MyConnection.createClient(socketPath).join();
		MyConnection server = serverTask.join();
		assertEquals(42, server.ping(42));
		assertEquals(727, client.ping(727));
		assertEquals(123, server.ping(123));
		assertEquals(999, client.ping(999));
		client.close();
		server.closeTask.join();
		client.closeTask.join();
		Files.deleteIfExists(socketPath);
	}
}
