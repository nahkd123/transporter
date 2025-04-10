package io.github.nahkd123.transporter.serialize;

import java.nio.ByteBuffer;

record NullableCodec<T>(BufferCodec<T> base) implements BufferCodec<T> {
	@Override
	public void encode(T value, ByteBuffer buffer) {
		buffer.put(value != null ? (byte) 1 : 0);
		if (value != null) base.encode(value, buffer);
	}

	@Override
	public T decode(ByteBuffer buffer) {
		boolean notNull = buffer.get() != 0;
		return notNull ? base.decode(buffer) : null;
	}
}
