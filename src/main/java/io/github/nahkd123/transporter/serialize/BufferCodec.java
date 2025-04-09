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
package io.github.nahkd123.transporter.serialize;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/**
 * <p>
 * Buffer codec unifies {@link BufferEncoder} and {@link BufferDecoder} into a
 * single {@link BufferCodec}. Common types like {@link #I8}, {@link #I16},
 * {@link #I32}, {@link #I64}, {@link #VUINT}, {@link #LPBYTES} and
 * {@link #UTF8} are provided and can be composed into complex types with
 * {@link #map(Function, Function)}, {@link #asSequence(int)},
 * {@link #asVarSequence(BufferCodec)} and {@code tupleOf()} methods.
 * </p>
 * <p>
 * Codecs are <em>immutable</em> and are usually provided from static final
 * fields.
 * </p>
 * 
 * @param <T> Type of object that will be encoded or decoded.
 * @see #of(BufferEncoder, BufferDecoder)
 * @see #map(Function, Function)
 * @see #asSequence(int)
 * @see #asVarSequence(BufferCodec)
 * @see #tupleOf(BufferCodec, Function, TupleFactory1)
 */
public interface BufferCodec<T> extends BufferEncoder<T>, BufferDecoder<T> {
	/**
	 * <p>
	 * Map codec to new target object type.
	 * </p>
	 * 
	 * @param <U>      Type of new object.
	 * @param forward  Forward mapping function to convert to target type.
	 * @param backward Backward mapping function to convert to source type.
	 * @return A new codec that encode/decode target type.
	 */
	default <U> BufferCodec<U> map(Function<T, U> forward, Function<U, T> backward) {
		return new MapCodec<>(this, forward, backward);
	}

	/**
	 * <p>
	 * Create fixed sequence of objects.
	 * </p>
	 * 
	 * @param size The number of elements.
	 * @return A new codec that encode/decode fixed sequence of objects.
	 */
	default BufferCodec<List<T>> asSequence(int size) {
		return new SequenceCodec<>(this, size);
	}

	/**
	 * <p>
	 * Create sequence of objects with prefixed length.
	 * </p>
	 * 
	 * @param lengthType The type of length value.
	 * @return A new codec that encode/decode variable sequence of objects.
	 */
	default BufferCodec<List<T>> asVarSequence(BufferCodec<? extends Number> lengthType) {
		return new VariableSequenceCodec<>(this, lengthType);
	}

	/**
	 * <p>
	 * Create a new buffer codec from a pair of encoder and decoder.
	 * </p>
	 * 
	 * @param <T>     Type of object.
	 * @param encoder The encoder that encode object to {@link ByteBuffer}.
	 * @param decoder The decoder that decode object from {@link ByteBuffer}.
	 * @return The codec.
	 */
	static <T> BufferCodec<T> of(BufferEncoder<T> encoder, BufferDecoder<T> decoder) {
		return new BufferCodecImpl<>(encoder, decoder);
	}

	BufferCodec<Byte> I8 = of((v, b) -> b.put(v), ByteBuffer::get);
	BufferCodec<Short> I16 = of((v, b) -> b.putShort(v), ByteBuffer::getShort);
	BufferCodec<Integer> I32 = of((v, b) -> b.putInt(v), ByteBuffer::getInt);
	BufferCodec<Long> I64 = of((v, b) -> b.putLong(v), ByteBuffer::getLong);

	/**
	 * <p>
	 * Variable-length unsigned {@code int}. Only encode positive values.
	 * </p>
	 */
	BufferCodec<Integer> VUINT = of(
		(v, b) -> {
			if (v < 0) throw new IllegalArgumentException("Value %d is negative (encoding vuint)".formatted(v));

			while (v != 0) {
				int bv = v & 0x7F;
				v >>= 7;
				if (v != 0) bv |= 0x80;
				b.put((byte) bv);
			}
		},
		b -> {
			int v = 0, bv, shift = 0;

			do {
				bv = b.get() & 0xFF;
				v |= (bv & 0x7F) << shift;
				shift += 7;
			} while ((bv & 0x80) != 0);

			return v;
		});

	/**
	 * <p>
	 * Variable-length prefixed byte array.
	 * </p>
	 */
	BufferCodec<byte[]> LPBYTES = of(
		(v, b) -> {
			VUINT.encode(v.length, b);
			b.put(b);
		},
		b -> {
			int length = VUINT.decode(b);
			byte[] bs = new byte[length];
			b.get(bs);
			return bs;
		});

	/**
	 * <p>
	 * Variable-length prefixed UTF-8 string.
	 * </p>
	 */
	BufferCodec<String> UTF8 = LPBYTES.map(
		bs -> new String(bs, StandardCharsets.UTF_8),
		s -> s.getBytes(StandardCharsets.UTF_8));

	// These repetitive code may be generated by script. There is no way I am
	// manually writing all of these methods.

	/**
	 * <p>
	 * Create a new codec for tuple-style object. A tuple have different type for
	 * each element. Example tuples are {@code (int, long)} or
	 * {@code (int, boolean, String)}.
	 * </p>
	 * <p>
	 * {@link BufferCodec} provides up to 8 different values for a single tuple. If
	 * your tuple is longer than 8, you might want to refactor your tuple class into
	 * smaller tuples, or implement manual reading and writing of tuple values using
	 * {@link #of(BufferEncoder, BufferDecoder)}.
	 * </p>
	 * 
	 * @see #tupleOf(BufferCodec, Function, TupleFactory1)
	 * @see #tupleOf(BufferCodec, Function, BufferCodec, Function, TupleFactory2)
	 * @see #tupleOf(BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, TupleFactory3)
	 * @see #tupleOf(BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, BufferCodec, Function, TupleFactory4)
	 * @see #tupleOf(BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, BufferCodec, Function, BufferCodec, Function, TupleFactory5)
	 * @see #tupleOf(BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, TupleFactory6)
	 * @see #tupleOf(BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, BufferCodec, Function, TupleFactory7)
	 * @see #tupleOf(BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, BufferCodec, Function, BufferCodec, Function, BufferCodec,
	 *      Function, BufferCodec, Function, BufferCodec, Function, TupleFactory8)
	 */
	static <P1, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, TupleFactory1<P1, T> factory) {
		return of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
			},
			b -> factory.create(f1.decode(b)));
	}

	static <P1, P2, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, BufferCodec<P2> f2, Function<T, P2> g2, TupleFactory2<P1, P2, T> factory) {
		return of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
				f2.encode(g2.apply(v), b);
			},
			b -> factory.create(f1.decode(b), f2.decode(b)));
	}

	static <P1, P2, P3, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, BufferCodec<P2> f2, Function<T, P2> g2, BufferCodec<P3> f3, Function<T, P3> g3, TupleFactory3<P1, P2, P3, T> factory) {
		return BufferCodec.of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
				f2.encode(g2.apply(v), b);
				f3.encode(g3.apply(v), b);
			},
			b -> factory.create(
				f1.decode(b),
				f2.decode(b),
				f3.decode(b)));
	}

	static <P1, P2, P3, P4, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, BufferCodec<P2> f2, Function<T, P2> g2, BufferCodec<P3> f3, Function<T, P3> g3, BufferCodec<P4> f4, Function<T, P4> g4, TupleFactory4<P1, P2, P3, P4, T> factory) {
		return BufferCodec.of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
				f2.encode(g2.apply(v), b);
				f3.encode(g3.apply(v), b);
				f4.encode(g4.apply(v), b);
			},
			b -> factory.create(
				f1.decode(b),
				f2.decode(b),
				f3.decode(b),
				f4.decode(b)));
	}

	static <P1, P2, P3, P4, P5, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, BufferCodec<P2> f2, Function<T, P2> g2, BufferCodec<P3> f3, Function<T, P3> g3, BufferCodec<P4> f4, Function<T, P4> g4, BufferCodec<P5> f5, Function<T, P5> g5, TupleFactory5<P1, P2, P3, P4, P5, T> factory) {
		return BufferCodec.of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
				f2.encode(g2.apply(v), b);
				f3.encode(g3.apply(v), b);
				f4.encode(g4.apply(v), b);
				f5.encode(g5.apply(v), b);
			},
			b -> factory.create(
				f1.decode(b),
				f2.decode(b),
				f3.decode(b),
				f4.decode(b),
				f5.decode(b)));
	}

	static <P1, P2, P3, P4, P5, P6, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, BufferCodec<P2> f2, Function<T, P2> g2, BufferCodec<P3> f3, Function<T, P3> g3, BufferCodec<P4> f4, Function<T, P4> g4, BufferCodec<P5> f5, Function<T, P5> g5, BufferCodec<P6> f6, Function<T, P6> g6, TupleFactory6<P1, P2, P3, P4, P5, P6, T> factory) {
		return BufferCodec.of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
				f2.encode(g2.apply(v), b);
				f3.encode(g3.apply(v), b);
				f4.encode(g4.apply(v), b);
				f5.encode(g5.apply(v), b);
				f6.encode(g6.apply(v), b);
			},
			b -> factory.create(
				f1.decode(b),
				f2.decode(b),
				f3.decode(b),
				f4.decode(b),
				f5.decode(b),
				f6.decode(b)));
	}

	static <P1, P2, P3, P4, P5, P6, P7, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, BufferCodec<P2> f2, Function<T, P2> g2, BufferCodec<P3> f3, Function<T, P3> g3, BufferCodec<P4> f4, Function<T, P4> g4, BufferCodec<P5> f5, Function<T, P5> g5, BufferCodec<P6> f6, Function<T, P6> g6, BufferCodec<P7> f7, Function<T, P7> g7, TupleFactory7<P1, P2, P3, P4, P5, P6, P7, T> factory) {
		return BufferCodec.of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
				f2.encode(g2.apply(v), b);
				f3.encode(g3.apply(v), b);
				f4.encode(g4.apply(v), b);
				f5.encode(g5.apply(v), b);
				f6.encode(g6.apply(v), b);
				f7.encode(g7.apply(v), b);
			},
			b -> factory.create(
				f1.decode(b),
				f2.decode(b),
				f3.decode(b),
				f4.decode(b),
				f5.decode(b),
				f6.decode(b),
				f7.decode(b)));
	}

	static <P1, P2, P3, P4, P5, P6, P7, P8, T> BufferCodec<T> tupleOf(BufferCodec<P1> f1, Function<T, P1> g1, BufferCodec<P2> f2, Function<T, P2> g2, BufferCodec<P3> f3, Function<T, P3> g3, BufferCodec<P4> f4, Function<T, P4> g4, BufferCodec<P5> f5, Function<T, P5> g5, BufferCodec<P6> f6, Function<T, P6> g6, BufferCodec<P7> f7, Function<T, P7> g7, BufferCodec<P8> f8, Function<T, P8> g8, TupleFactory8<P1, P2, P3, P4, P5, P6, P7, P8, T> factory) {
		return BufferCodec.of(
			(v, b) -> {
				f1.encode(g1.apply(v), b);
				f2.encode(g2.apply(v), b);
				f3.encode(g3.apply(v), b);
				f4.encode(g4.apply(v), b);
				f5.encode(g5.apply(v), b);
				f6.encode(g6.apply(v), b);
				f7.encode(g7.apply(v), b);
				f8.encode(g8.apply(v), b);
			},
			b -> factory.create(
				f1.decode(b),
				f2.decode(b),
				f3.decode(b),
				f4.decode(b),
				f5.decode(b),
				f6.decode(b),
				f7.decode(b),
				f8.decode(b)));
	}

	@FunctionalInterface
	interface TupleFactory1<P1, T> {
		T create(P1 p1);
	}

	@FunctionalInterface
	interface TupleFactory2<P1, P2, T> {
		T create(P1 p1, P2 p2);
	}

	@FunctionalInterface
	interface TupleFactory3<P1, P2, P3, T> {
		T create(P1 p1, P2 p2, P3 p3);
	}

	@FunctionalInterface
	interface TupleFactory4<P1, P2, P3, P4, T> {
		T create(P1 p1, P2 p2, P3 p3, P4 p4);
	}

	@FunctionalInterface
	interface TupleFactory5<P1, P2, P3, P4, P5, T> {
		T create(P1 p1, P2 p2, P3 p3, P4 p4, P5 p5);
	}

	@FunctionalInterface
	interface TupleFactory6<P1, P2, P3, P4, P5, P6, T> {
		T create(P1 p1, P2 p2, P3 p3, P4 p4, P5 p5, P6 p6);
	}

	@FunctionalInterface
	interface TupleFactory7<P1, P2, P3, P4, P5, P6, P7, T> {
		T create(P1 p1, P2 p2, P3 p3, P4 p4, P5 p5, P6 p6, P7 p7);
	}

	@FunctionalInterface
	interface TupleFactory8<P1, P2, P3, P4, P5, P6, P7, P8, T> {
		T create(P1 p1, P2 p2, P3 p3, P4 p4, P5 p5, P6 p6, P7 p7, P8 p8);
	}
}
