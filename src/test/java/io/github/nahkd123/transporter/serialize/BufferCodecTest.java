
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

	@Test
	void testComplex() {
		record Customer(String name, int age, String rewardCode) {
		}

		BufferCodec<Customer> customerCodec = BufferCodec.tupleOf(
			BufferCodec.UTF8, Customer::name,
			BufferCodec.I32, Customer::age,
			BufferCodec.UTF8.asNullable(), Customer::rewardCode,
			Customer::new);

		ByteBuffer buf = ByteBuffer.allocate(128);
		Customer alice = new Customer("Alice", 42, null);
		customerCodec.encode(alice, buf);
		buf.flip();
		assertEquals(alice, customerCodec.decode(buf));
		buf.clear();

		Customer bob = new Customer("Bob", 27, "727-727");
		customerCodec.encode(bob, buf);
		buf.flip();
		assertEquals(bob, customerCodec.decode(buf));
		buf.clear();
	}
}
