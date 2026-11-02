"""Disposable emulator fixture: echo through SOCKS5, never contact the target."""
import asyncio
import socket


class DatagramEcho(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        # Preserve the SOCKS5 destination header along with the payload.
        self.transport.sendto(data, address)


async def handle(reader, writer):
    udp = None
    try:
        version, count = await reader.readexactly(2)
        assert version == 5
        assert 0 in await reader.readexactly(count)
        writer.write(b"\x05\x00")
        await writer.drain()
        version, command, _, atyp = await reader.readexactly(4)
        assert version == 5
        if atyp == 1:
            await reader.readexactly(4)
        elif atyp == 4:
            await reader.readexactly(16)
        elif atyp == 3:
            await reader.readexactly((await reader.readexactly(1))[0])
        else:
            raise ValueError("address type")
        await reader.readexactly(2)
        if command == 3:
            udp, _ = await asyncio.get_running_loop().create_datagram_endpoint(
                DatagramEcho, local_addr=("0.0.0.0", 0)
            )
            port = udp.get_extra_info("sockname")[1]
            writer.write(b"\x05\x00\x00\x01" + socket.inet_aton("10.0.2.2") + port.to_bytes(2, "big"))
            await writer.drain()
            await reader.read()
        else:
            assert command == 1
            writer.write(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
            await writer.drain()
            while data := await reader.read(65536):
                writer.write(data)
                await writer.drain()
    except (AssertionError, ValueError, ConnectionError, asyncio.IncompleteReadError):
        pass
    finally:
        if udp:
            udp.close()
        writer.close()
        await writer.wait_closed()


async def main():
    async with await asyncio.start_server(handle, "0.0.0.0", 18080) as server:
        print("SOCKS echo fixture ready", flush=True)
        await server.serve_forever()


asyncio.run(main())
