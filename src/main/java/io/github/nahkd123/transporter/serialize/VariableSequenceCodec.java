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
import java.util.List;

record VariableSequenceCodec<T>(BufferCodec<T> base, BufferCodec<? extends Number> lengthType) implements BufferCodec<List<T>> {
	@Override
	public void encode(List<T> value, ByteBuffer buffer) {
		lengthType.castAndEncode(value.size(), buffer);
		for (T element : value) base.encode(element, buffer);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<T> decode(ByteBuffer buffer) {
		int length = lengthType.decode(buffer).intValue();
		T[] value = (T[]) new Object[length];
		for (int i = 0; i < length; i++) value[i] = base.decode(buffer);
		return List.of(value);
	}
}
