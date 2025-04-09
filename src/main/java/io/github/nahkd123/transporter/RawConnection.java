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
import java.nio.channels.Selector;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * <p>
 * A raw connection for handling packets read and write, as well as connection
 * state (either opened or "appears closed"). The connection is not bounds to
 * any byte channel; closing this connection directly only request close to
 * implementation of raw connection. This is a low-level connection class; for
 * higher level, see {@link TransporterConnection}.
 * </p>
 * <p>
 * To "write" a packet, use
 * {@link #queueRawPacketWrite(PacketMode, int, int, Consumer)} method. This
 * method is not visible to public and it is up to implementation to expose it
 * for consumption (like an API), provide indirect packet write method, or hide
 * it. The write callback when queueing a new packet write should be
 * thread-safe, as it may be called on different thread (this is more common
 * than interleaving everything in one thread). Note that the write callback
 * will not be called immediately, but only when there is a need to write a new
 * packet to channel.
 * </p>
 * <p>
 * To actually read or write packets, you must call
 * {@link #channelRead(ByteChannel)} and {@link #channelWrite(ByteChannel)}.
 * Typically you would interleave these methods in a networking thread, where
 * the byte channel is non-blocking (which returns no bytes read when there is
 * no data to read from channel at the moment of read request), but it can be
 * used individually if you only need to read or write packets (but not both).
 * When interleaving these methods, the returned values can be used to determine
 * whether to put the thread to sleep for a bit (usually around 1ms), or
 * continue the loop immediately.
 * </p>
 * {@snippet :
 * ByteChannel ch;
 * RawConnection conn;
 * 
 * while (!conn.isClosed()) {
 * 	boolean continueInstantly = conn.channelRead(ch);
 * 	continueInstantly |= conn.channelWrite(ch);
 * 	if (!continueInstantly) LockSupport.parkNanos(1000000L);
 * }
 * }
 * <p>
 * If you are using {@link Selector} for multiplexing read/write (usually for
 * non-blocking IO), you can also use {@link #channelRead(ByteChannel)} and
 * {@link #channelWrite(ByteChannel)} based on selection key's state.
 * </p>
 * {@snippet :
 * Selector selector;
 * selector.select();
 * Iterator&lt;SelectionKey&gt; keys = selector.selectedKeys().iterator();
 * 
 * while (keys.hasNext()) {
 * 	SelectionKey key = keys.next();
 * 	if (key.attachment() == null) key.attach(new RawConnectionImpl());
 * 	if (key.isReadable()) ((RawConnection) key.attachment()).channelRead((ByteChannel) key.channel());
 * 	if (key.isWritable()) ((RawConnection) key.attachment()).channelWrite((ByteChannel) key.channel());
 * }
 * }
 * <p>
 * <b>Packet header format</b>: The packet data is encapsulated by packet
 * header, following the header format:
 * <ol>
 * <li><b>Packet mode (u16)</b>: This is the ordinal of {@link PacketMode};</li>
 * <li><b>Packet body size (u16)</b>: This is the number of bytes in packet
 * body. Up to 65536 bytes for packet body;</li>
 * <li><b>Packet type ID (u32)</b>: This is the numerical ID of packet type. The
 * ID is up to implementation of {@link RawConnection} to determine;</li>
 * <li><b>Request ID (u32)</b>: This is the request ID of packet. For notify
 * packets, this request ID have no effect. For request packets, the ID is up to
 * implementation to determine (usually the connection maintain a counter that
 * increases by 1 on every request). For response packet, the ID must match with
 * request ID of request packet that the response is replying to.</li>
 * </ol>
 * Followed by packet header is packet body, whose size is determined in packet
 * header.
 * </p>
 * 
 * @see #createConnectionBuffer()
 * @see #onRawPacket(PacketMode, int, int, ByteBuffer)
 * @see #onClose(boolean, Throwable)
 * @see #queueRawPacketWrite(PacketMode, int, int, Consumer)
 */
public abstract class RawConnection implements AutoCloseable {
	private static final int STATE_HEADER = 0;
	private static final int STATE_BODY = 1;
	private static final int HEADER_SIZE = 2 + 2 + 4 + 4; // mode + size + type + reqId

	private record Outgoing(PacketMode mode, int type, int reqId, Consumer<ByteBuffer> writer) {
	}

	private boolean closed = false;
	private ByteBuffer readBuffer = null;
	private int state;
	private PacketMode mode;
	private int size;
	private int type;
	private int reqId;

	private ByteBuffer writeBuffer = null;
	private Queue<Outgoing> writeQueue = null;

	/**
	 * <p>
	 * Called when preparing buffers for reading or writing data. This will allocate
	 * a new byte buffer for storing or reading data. You can configure maximum
	 * bytes per packet and byte order here. Read buffer and write buffer are not
	 * the same. The maximum number of bytes a single packet body can hold is 65536,
	 * which means the size of connection buffers should be 65548 (12 bytes header +
	 * 64k body). Any buffer with size larger than this will have its extra unused.
	 * </p>
	 * {@snippet :
	 * &#64;Override
	 * protected ByteBuffer createConnectionBuffer() {
	 * 	return ByteBuffer.allocate(16384).byteOrder(ByteOrder.LITTLE_ENDIAN);
	 * }
	 * }
	 * 
	 * @return The connection buffer.
	 */
	protected abstract ByteBuffer createConnectionBuffer();

	/**
	 * <p>
	 * Called when connection is closed, whether it is remotely closed (end of
	 * stream returned from channel) or directly closed (manually requested). In the
	 * case of directly closed, you might have to close the underlying channel as
	 * well.
	 * </p>
	 * 
	 * @param remote Whether the connection is closed remotely.
	 * @param error  Error if connection is closed with error.
	 */
	protected abstract void onClose(boolean remote, Throwable error);

	/**
	 * <p>
	 * Called when a raw packet is received from this connection. The implementation
	 * is supposed to perform relative read operation on the buffer. The
	 * implementation must also not hold the buffer, as it will be recycled to read
	 * another raw packet (you may read the data first then queue it somewhere for
	 * processing later).
	 * </p>
	 * 
	 * @param mode   The packet mode.
	 * @param type   The numerical ID of packet type. This is predefined in
	 *               implementation's protocol.
	 * @param reqId  The peer's request ID. Responses must have the same request IDs
	 *               as provided by peer. Notifications does not need request ID.
	 * @param buffer The buffer with raw packet data stored.
	 */
	protected abstract void onRawPacket(PacketMode mode, int type, int reqId, ByteBuffer buffer);

	/**
	 * <p>
	 * Queue a new outgoing raw packet to be written upon calling
	 * {@link #channelWrite(ByteChannel)}. If the connection is marked as closed,
	 * this method will do nothing. The write callback should be thread-safe and the
	 * size of packet will be calculated based on how much the {@code position}
	 * pointer in {@link ByteBuffer} advanced. The preferred way to write packet
	 * data is to use relative put methods.
	 * </p>
	 * {@snippet :
	 * queueRawPacketWrite(PacketMode.NOTIFY, 0xDEAD, 0, b -> b.putInt(42).putLong(1337L));
	 * }
	 * 
	 * @param mode   The packet mode.
	 * @param type   The numerical ID of packet type. This is predefined in
	 *               implementation's protocol.
	 * @param reqId  The peer's request ID.Responses must have the same request IDs
	 *               as provided by peer. Notifications does not need request ID.
	 * @param writer The callback that will be called in
	 *               {@link #channelWrite(ByteChannel)} when writing packets to
	 *               channel.
	 */
	protected void queueRawPacketWrite(PacketMode mode, int type, int reqId, Consumer<ByteBuffer> writer) {
		if (closed) return;
		writeQueue.add(new Outgoing(mode, type, reqId, writer));
	}

	/**
	 * <p>
	 * Perform reading packets from byte channel until end of stream reached, the
	 * connection is marked as closed or no bytes read from channel. The latter
	 * requires the channel to be in non-blocking mode.
	 * </p>
	 * 
	 * @param channel The byte channel to read.
	 * @return If this method actually read something from channel.
	 * @throws IOException If channel is closed with error.
	 */
	public boolean channelRead(ByteChannel channel) throws IOException {
		if (closed) return false;
		ensurePrepared();
		boolean didSomething = false;

		try {
			while (!closed) {
				while (readBuffer.hasRemaining()) {
					int bytesRead = channel.read(readBuffer);
					if (bytesRead == 0) return didSomething;
					if (bytesRead == -1) {
						closed = true;
						onClose(true, null);
						return true;
					}
				}

				readBuffer.flip();
				didSomething = true;

				switch (state) {
				case STATE_HEADER:
					mode = PacketMode.fromId(readBuffer.getShort() & 0xFFFF);
					size = readBuffer.getShort() & 0xFFFF;
					type = readBuffer.getInt();
					reqId = readBuffer.getInt();
					state = STATE_BODY;
					readBuffer.limit(HEADER_SIZE + size);
					break;
				case STATE_BODY:
					readBuffer.position(HEADER_SIZE);
					onRawPacket(mode, type, reqId, readBuffer);
					state = STATE_HEADER;
					readBuffer.clear().limit(HEADER_SIZE);
					break;
				}
			}

			return didSomething;
		} catch (Throwable t) {
			closed = true;
			onClose(false, t);
			throw t instanceof IOException ioe ? ioe : new IOException("Error while reading from channel", t);
		}
	}

	/**
	 * <p>
	 * Perform writing packets to byte channel until the connection is marked as
	 * closed, no more outgoing packets or no bytes can be written.
	 * </p>
	 * 
	 * @param channel The byte channel to write.
	 * @return If this method actually written something to channel.
	 * @throws IOException If error occurred while writing packets.
	 */
	public boolean channelWrite(ByteChannel channel) throws IOException {
		if (closed) return false;
		ensurePrepared();
		boolean didSomething = false;

		try {
			while (!closed) {
				while (writeBuffer.hasRemaining()) {
					int bytesWritten = channel.write(writeBuffer);
					if (bytesWritten == 0) return didSomething;
				}

				Outgoing outgoing = writeQueue.poll();
				if (outgoing == null) return didSomething;

				writeBuffer.clear()
					.putShort(0, (short) outgoing.mode.ordinal())
					.putInt(4, outgoing.type)
					.putInt(8, outgoing.reqId)
					.position(HEADER_SIZE);
				outgoing.writer.accept(writeBuffer);
				writeBuffer
					.putShort(2, (short) (writeBuffer.position() - HEADER_SIZE))
					.flip();
				didSomething = true;
			}

			return didSomething;
		} catch (Throwable t) {
			closed = true;
			onClose(false, t);
			throw t instanceof IOException ioe ? ioe : new IOException("Error while writing to channel", t);
		}
	}

	private void ensurePrepared() {
		if (readBuffer == null) {
			readBuffer = createConnectionBuffer();
			writeBuffer = createConnectionBuffer();
			if (readBuffer == null || writeBuffer == null)
				throw new NullPointerException("createConnectionBuffer() returns null");

			readBuffer.clear().limit(HEADER_SIZE);
			state = STATE_HEADER;

			writeBuffer.clear().limit(0);
			writeQueue = new ConcurrentLinkedQueue<>();
		}
	}

	/**
	 * <p>
	 * Check whether the connection is considered to be "closed".
	 * </p>
	 * 
	 * @return Whether the connection is closed.
	 */
	public boolean isClosed() { return closed; }

	/**
	 * <p>
	 * Set the close state of this connection. The state is automatically changed
	 * before {@link #onClose(boolean, Throwable)} is called.
	 * </p>
	 * 
	 * @param closed The new close state.
	 */
	protected void setClosed(boolean closed) { this.closed = closed; }

	/**
	 * <p>
	 * Directly request the connection to be closed.
	 * </p>
	 */
	@Override
	public void close() {
		if (closed) return;
		onClose(false, null);
		closed = true;
	}

	public static enum PacketMode {
		/**
		 * <p>
		 * Packet mode for sending request. Peer must response either
		 * {@link #RESPONSE_SUCCEED} or {@link #RESPONSE_FAILED} upon completion, using
		 * the same request ID as request packet.
		 * </p>
		 */
		REQUEST,
		/**
		 * <p>
		 * Packet mode for responses with succeed status. Request ID of response packets
		 * must have the same request ID as provided by peer.
		 * </p>
		 */
		RESPONSE_SUCCEED,
		/**
		 * <p>
		 * Packet mode for responses with failed status. Request ID of response packets
		 * must have the same request ID as provided by peer. The format of error packet
		 * body is for implementation to define.
		 * </p>
		 */
		RESPONSE_FAILED,
		/**
		 * <p>
		 * Packet mode for notifications. Notification does not wait for response from
		 * peer.
		 * </p>
		 */
		NOTIFY;

		private static final PacketMode[] MODES = values();

		private static PacketMode fromId(int id) {
			if (id < 0 || id >= MODES.length)
				throw new IllegalArgumentException("Unknown mode ID 0x%02x".formatted(id));
			return MODES[id];
		}
	}
}
