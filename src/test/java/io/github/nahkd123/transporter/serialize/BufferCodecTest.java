
package io.github.nahkd123.transporter.serialize;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class BufferCodecTest {
	@Test
	void testUtf8() {
		ByteBuffer buf = ByteBuffer.allocate(128);
		BufferCodec.UTF8.encode("Hello world", buf);
		buf.flip();
		assertEquals("Hello world", BufferCodec.UTF8.decode(buf));
	}

	@Test
	void testVuint() {
		ByteBuffer buf = ByteBuffer.allocate(128);
		BufferCodec.VUINT.encode(0, buf);
		buf.flip();
		assertEquals(0, BufferCodec.VUINT.decode(buf));
	}
}
