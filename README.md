# Transporter
_Library with abstraction layers for easier socket programming_

Transporter consists of 2 abstraction layers:

- `RawConnection`: Handle packets at low level, where it work directly with bytes and channels. Request,
response and notification are treated the same;
- `TransporterConnection`: Handle packets at high level, basically pumping data from `RawConnection`,
deserialize them to packets and then supply them to your implementation for processing.

## Using Transporter
### Using Maven
```xml
<repositories>
	<repository>
		<id>jitpack</id>
		<url>https://jitpack.io/</url>
	</repository>
</repositories>
```

```xml
<dependency>
	<groupId>com.github.nahkd123</groupId>
	<artifactId>transporter</artifactId>
	<version>1.0.0</version>
</dependency>
```

### Using Gradle
```groovy
repositories {
	maven { url = 'https://jitpack.io/' }
}
```

```groovy
dependencies {
	implementation 'com.github.nahkd123:transporter:1.0.0'
}
```

## Example usage
### Simple TCP server and client
```java
// Shared between client and server
class MyConnection extends TransporterConnection {
	MyConnection() {
		// You must register packet types before calls to channelRead() or channelWrite()
		registerPacket(0x00, MyRequest.class, MyRequest.CODEC);
		registerPacket(0x01, MyResponse.class, MyResponse.CODEC);
	}

	@Override
	protected ByteBuffer createConnectionBuffer() {
		// Additionally you may want to set byte order
		return ByteBuffer.allocate(256);
	}

	@Override
	protected void onRequest(TransporterConnection.Request req) throws Throwable {
		switch (req.packet()) {
			case MyRequest():
				req.responseSuccess(new MyResponse());
				break;
			default:
				req.responseFailure(reqId, "Not implemented: %s".formatted(data.getClass()));
				break;
		}
	}

	// Expose public method for queueing packet
	public CompletableFuture<MyResponse> writeRequest() {
		return this.<MyResponse>queueRequest(new MyRequest());
	}

	// I/O loop method that interleaving channel read and write
	public void ioLoop(ByteChannel ch) {
		while (!isClosed()) {
			boolean b = channelRead(ch);
			b |= channelWrite(ch);
			if (!b) LockSupport.parkNano(1000000L); // optional - prevent high CPU usage
		}
	}
}

// Server process
CompletableFuture<MyConnection> onConnectTask = new CompletableFuture<>();
Thread.startVirtualThread(() -> {
	try {
		ServerSocketChannel listener = ServerSocketChannel.open();
		listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 27272));
		SocketChannel channel = listener.accept();
		listener.close();
		channel.configureBlocking(false);
		MyConnection conn = new MyConnection();
		onConnectTask.complete(conn);
		conn.ioLoop(channel);
	} catch (IOException e) {
		throw new UncheckedIOException(e);
	}
});

MyConnection serverConnection = conn.join(); // Do something with this

// Client process
CompletableFuture<MyConnection> onConnectTask = new CompletableFuture<>();
Thread.startVirtualThread(() -> {
	try {
		SocketChannel channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), 27272));
		channel.configureBlocking(false);
		MyConnection conn = new MyConnection();
		onConnectTask.complete(conn);
		conn.ioLoop(channel);
	} catch (IOException e) {
		throw new UncheckedIOException(e);
	}
});

try (MyConnection clientConnection = conn.join()) {
	clientConnection.writeRequest().join();
}
```

### Using `BufferCodec`
```java
record Duo(int a, long b) {
	static final BufferCodec<Duo> CODEC = BufferCodec.tupleOf(
		BufferCodec.I32, Duo::a,
		BufferCodec.I64, Duo::b,
		Duo::new);
}

record CustomerInfo(String name, int age, String phone) {
	static final BufferCodec<CustomerInfo> CODEC = BufferCodec.tupleOf(
		BufferCodec.UTF8, CustomerInfo::name,
		BufferCodec.I32, CustomerInfo::age,
		BufferCodec.UTF8, CustomerInfo::phone,
		CustomerInfo::new);
}

BufferCodec<UUID> UUID = BufferCodec.I64
	.asSequence(2)
	.map(
		list -> new UUID(list.get(0), list.get(1)),
		uuid -> List.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));
```

## License
MIT License. See [LICENSE][license-legal] for full legal text.

[license-legal]: ./LICENSE