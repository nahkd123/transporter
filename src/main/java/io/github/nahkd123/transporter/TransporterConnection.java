/*
 * The MIT License (MIT)
 * 
 * Copyright © 2025 Tran Huu An
 * 
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the “Software”), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.github.nahkd123.transporter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.github.nahkd123.transporter.serialize.BufferCodec;
import io.github.nahkd123.transporter.serialize.BufferDecoder;
import io.github.nahkd123.transporter.serialize.BufferEncoder;

/**
 * <p>
 * A Transporter connection sits on top of {@link RawConnection}. Implementation
 * must register all packets that it wish to send and receive through
 * {@link #registerPacket(int, Class, BufferCodec)} or
 * {@link #registerPacket(int, Class, BufferEncoder, BufferDecoder)} methods.
 * All requests from peer must be resolved in {@link #onRequest(int, Object)},
 * or peer will wait for the response forever (until connection is closed,
 * actually).
 * </p>
 * {@snippet :
 * public MyConnection() {
 * 	registerPacket(0x00, MyPacket.class, MyPacket.CODEC);
 * 	registerPacket(0x01, MyPacketResponse.class, MyPacketResponse.CODEC);
 * }
 * 
 * &#64;Override
 * protected void onRequest(int rid, Object data) {
 * 	switch (data) {
 * 	case MyPacket():
 * 		queueSucceed(new MyPacketResponse());
 * 		break;
 * 	default:
 * 		throw new RuntimeException("I don't know %s".formatted(data));
 * 	}
 * }
 * }
 * <p>
 * To send request, use {@link #queueRequest(Object)}. The request data will
 * only be written when {@link #channelWrite(ByteChannel)} is called. To receive
 * any kind of packet, use {@link #channelRead(ByteChannel)}. Note that calls to
 * {@link #channelRead(ByteChannel)} and {@link #channelWrite(ByteChannel)} are
 * meant to be interleaved in a loop. A typical way to handle connection is to
 * spawn a new virtual thread calling these 2 methods repeatedly until
 * {@link #isClosed()} become {@code true}. See more about this in
 * {@link RawConnection}.
 * </p>
 * 
 * @see #registerPacket(int, Class, BufferCodec)
 * @see #registerPacket(int, Class, BufferEncoder, BufferDecoder)
 * @see #registerPacketListener(Class, Consumer)
 * @see #onRequest(int, Object)
 * @see #onNotification(Object)
 * @see #onPacket(PacketMode, int, Object)
 */
public abstract class TransporterConnection extends RawConnection {
	private final Map<Class<?>, BufferEncoder<?>> encoders = new HashMap<>();
	private final Map<Class<?>, Integer> packetIds = new HashMap<>();
	private final Map<Integer, BufferDecoder<?>> decoders = new HashMap<>();
	private final Map<Class<?>, List<Consumer<?>>> listeners = new HashMap<>();
	private final Map<Integer, CompletableFuture<?>> requests = new ConcurrentHashMap<>();
	private int reqIdCounter = 0;

	/**
	 * <p>
	 * Handle request received from peer. Upon completion, implementation must
	 * response with either {@link Request#responseSuccess(Object)} or
	 * {@link Request#responseFailure(String)}, or throw a {@link Throwable},
	 * otherwise the request task in peer will never be completed (until connection
	 * is closed).
	 * </p>
	 * 
	 * @param request The request.
	 * @throws Throwable Throw anything to response with failure.
	 *                   {@link Throwable#getMessage()} will be used as failure
	 *                   message.
	 * @see #queueSucceed(int, Object)
	 * @see #queueFailed(int, String)
	 */
	protected abstract void onRequest(Request request) throws Throwable;

	protected void onNotification(Object data) {}

	protected void onPacket(PacketMode mode, int reqId, Object data) {}

	protected void onUnknownRawPacket(PacketMode mode, int type, int reqId, ByteBuffer buffer) {
		if (mode == PacketMode.REQUEST) queueRawPacketWrite(
			PacketMode.RESPONSE_FAILED,
			type, reqId,
			b -> BufferCodec.UTF8.encode("Unknown packet ID: 0x%02x".formatted(type), b));
	}

	/**
	 * <p>
	 * Register a new packet type. Packet type must be registered in order to queue
	 * packets.
	 * </p>
	 * 
	 * @param <T>     Type of packet.
	 * @param type    Numerical ID of the packet type. Must be unique for each type.
	 * @param clazz   The class of packet.
	 * @param encoder The packet encoder.
	 * @param decoder The packet decoder.
	 * @see #registerPacket(int, Class, BufferCodec)
	 */
	protected <T> void registerPacket(int type, Class<T> clazz, BufferEncoder<T> encoder, BufferDecoder<T> decoder) {
		Objects.requireNonNull(clazz, "'clazz' is null");
		Objects.requireNonNull(encoder, "'encoder' is null");
		Objects.requireNonNull(decoder, "'decoder' is null");

		if (encoders.putIfAbsent(clazz, encoder) != null) {
			throw new IllegalArgumentException("Class already registered: %s".formatted(clazz));
		}

		if (decoders.putIfAbsent(type, decoder) != null) {
			encoders.remove(clazz);
			throw new IllegalArgumentException("Type ID already registered: %d (0x%02x)".formatted(type, type));
		}

		packetIds.put(clazz, type);
	}

	/**
	 * <p>
	 * Register a new packet type using codec. Packet type must be registered in
	 * order to queue packets.
	 * </p>
	 * 
	 * @param <T>   Type of packet.
	 * @param type  Numerical ID of the packet type. Must be unique for each type.
	 * @param clazz The class of packet.
	 * @param codec The codec of packet.
	 * @see #registerPacket(int, Class, BufferEncoder, BufferDecoder)
	 * @see BufferCodec
	 */
	protected <T> void registerPacket(int type, Class<T> clazz, BufferCodec<T> codec) {
		Objects.requireNonNull(clazz, "'clazz' is null");
		Objects.requireNonNull(codec, "'codec' is null");
		registerPacket(type, clazz, codec, codec);
	}

	/**
	 * <p>
	 * Register packet listener, which listens for packets. Listener must not queue
	 * any response packet upon being called, as this is being handled in
	 * {@link #onRequest(int, Object)}.
	 * </p>
	 * 
	 * @param <T>      Type of packet.
	 * @param clazz    Class of packet.
	 * @param callback Callback that will be called when received packet with
	 *                 specific type from peer.
	 */
	protected <T> void registerPacketListener(Class<T> clazz, Consumer<T> callback) {
		Objects.requireNonNull(clazz, "'clazz' is null");
		Objects.requireNonNull(callback, "'callback' is null");
		List<Consumer<?>> callbacks = listeners.computeIfAbsent(clazz, c -> new ArrayList<>());
		callbacks.add(callback);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	protected void onRawPacket(PacketMode mode, int type, int reqId, ByteBuffer buffer) {
		if (mode == PacketMode.RESPONSE_FAILED) {
			String message = BufferCodec.UTF8.decode(buffer);
			CompletableFuture<?> task = requests.remove(reqId);
			if (task != null) task.completeExceptionally(new RuntimeException(message));
		} else {
			BufferDecoder<?> decoder = decoders.get(type);

			if (decoder == null) {
				onUnknownRawPacket(mode, type, reqId, buffer);
				return;
			} else {
				Object data = decoder.decode(buffer);
				List<Consumer<?>> callbacks = listeners.get(data.getClass());
				if (callbacks != null) callbacks.forEach(c -> ((Consumer) c).accept(data));
				onPacket(mode, reqId, data);

				switch (mode) {
				case NOTIFY:
					onNotification(data);
					break;
				case REQUEST: {
					RequestImpl request = new RequestImpl(data, reqId);

					try {
						onRequest(request);
					} catch (Throwable t) {
						t.printStackTrace();
						request.responseFailure(t.getMessage());
					}

					break;
				}
				case RESPONSE_SUCCEED: {
					CompletableFuture<?> task = requests.remove(reqId);
					if (task != null) ((CompletableFuture) task).complete(data);
					break;
				}
				default:
					break;
				}
			}
		}
	}

	@Override
	protected void onClose(boolean remote, Throwable error) {
		Throwable t = new IOException(remote ? "Connection closed by peer" : "Connection closed", error);
		List.copyOf(requests.values()).forEach(r -> r.completeExceptionally(t));
		requests.clear();
	}

	private void queuePacket(PacketMode mode, int reqId, Object data) {
		BufferEncoder<?> encoder = encoders.get(data.getClass());
		if (encoder == null) throw new IllegalArgumentException("Class not registered: %".formatted(data.getClass()));
		int id = packetIds.get(data.getClass());
		queueRawPacketWrite(mode, id, reqId, buffer -> encoder.castAndEncode(data, buffer));
	}

	/**
	 * <p>
	 * Queue an outgoing notification.
	 * </p>
	 * 
	 * @param data The notification packet.
	 */
	protected void queueNotification(Object data) {
		Objects.requireNonNull(data, "'data' is null");
		queuePacket(PacketMode.NOTIFY, 0, data);
	}

	/**
	 * <p>
	 * Queue an outgoing request to peer.
	 * </p>
	 * 
	 * @param <T>     Type of response packet.
	 * @param request The request packet.
	 * @return The task that will be completed when received response from peer.
	 */
	protected <T> CompletableFuture<T> queueRequest(Object request) {
		Objects.requireNonNull(request, "'request' is null");
		CompletableFuture<T> task = new CompletableFuture<>();
		int reqId = reqIdCounter++;
		requests.put(reqId, task);
		queuePacket(PacketMode.REQUEST, reqId, request);
		return task;
	}

	public static interface Request {
		/**
		 * <p>
		 * Get the packet data from request.
		 * </p>
		 */
		Object packet();

		/**
		 * <p>
		 * Response to this request with success status.
		 * </p>
		 * 
		 * @param data The response packet.
		 */
		void responseSuccess(Object data);

		/**
		 * <p>
		 * Response to this request with failure status.
		 * </p>
		 * 
		 * @param message The error message.
		 */
		void responseFailure(String message);
	}

	private class RequestImpl implements Request {
		private Object packet;
		private int reqId;
		private boolean response = false;

		public RequestImpl(Object packet, int reqId) {
			this.packet = packet;
			this.reqId = reqId;
		}

		@Override
		public Object packet() {
			return packet;
		}

		@Override
		public void responseSuccess(Object data) {
			if (response) return;
			Objects.requireNonNull(data, "'data' is null");
			queuePacket(PacketMode.RESPONSE_SUCCEED, reqId, data);
		}

		@Override
		public void responseFailure(String message) {
			if (response) return;
			Objects.requireNonNull(message, "'message' is null");
			queueRawPacketWrite(
				PacketMode.RESPONSE_FAILED, 0, reqId,
				buffer -> BufferCodec.UTF8.encode(message, buffer));
		}
	}
}
