package com.genymobile.scrcpy;

import android.media.MediaCodec;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class Streamer {

    private static final long PACKET_FLAG_CONFIG = 1L << 63;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 62;

    private static final long AOPUSHDR = 0x5244485355504F41L; // "AOPUSHDR" in ASCII (little-endian)

    private final FileDescriptor fd;
    private final Codec codec;
    private final boolean sendCodecId;
    private final boolean sendFrameMeta;

    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    public Streamer(FileDescriptor fd, Codec codec, boolean sendCodecId, boolean sendFrameMeta) {
        this.fd = fd;
        this.codec = codec;
        this.sendCodecId = sendCodecId;
        this.sendFrameMeta = sendFrameMeta;
    }

    public Codec getCodec() {
        return codec;
    }

    public void writeHeader() throws IOException {
        if (sendCodecId) {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(codec.getId());
            buffer.flip();
            IO.writeFully(fd, buffer);
        }
    }

    public void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        if (codec == AudioCodec.OPUS) {
            fixOpusConfigPacket(codecBuffer, bufferInfo);
        }

        if (sendFrameMeta) {
            writeFrameMeta(fd, bufferInfo, codecBuffer.remaining());
        }

        IO.writeFully(fd, codecBuffer);
    }

    private void writeFrameMeta(FileDescriptor fd, MediaCodec.BufferInfo bufferInfo, int packetSize) throws IOException {
        headerBuffer.clear();

        long ptsAndFlags;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            ptsAndFlags = PACKET_FLAG_CONFIG; // non-media data packet
        } else {
            ptsAndFlags = bufferInfo.presentationTimeUs;
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                ptsAndFlags |= PACKET_FLAG_KEY_FRAME;
            }
        }

        headerBuffer.putLong(ptsAndFlags);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        IO.writeFully(fd, headerBuffer);
    }

    private static void fixOpusConfigPacket(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        // Here is an example of the config packet received for an OPUS stream:
        //
        // 00000000  41 4f 50 55 53 48 44 52  13 00 00 00 00 00 00 00  |AOPUSHDR........|
        // -------------- BELOW IS THE PART WE MUST PUT AS EXTRADATA  -------------------
        // 00000010  4f 70 75 73 48 65 61 64  01 01 38 01 80 bb 00 00  |OpusHead..8.....|
        // 00000020  00 00 00                                          |...             |
        // ------------------------------------------------------------------------------
        // 00000020           41 4f 50 55 53  44 4c 59 08 00 00 00 00  |   AOPUSDLY.....|
        // 00000030  00 00 00 a0 2e 63 00 00  00 00 00 41 4f 50 55 53  |.....c.....AOPUS|
        // 00000040  50 52 4c 08 00 00 00 00  00 00 00 00 b4 c4 04 00  |PRL.............|
        // 00000050  00 00 00                                          |...|
        //
        // Each "section" is prefixed by a 64-bit ID and a 64-bit length.

        boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        if (!isConfig) {
            return;
        }

        while (buffer.remaining() >= 16) {
            long id = buffer.getLong();
            long sizeLong = buffer.getLong();
            if (sizeLong < 0 || sizeLong >= 0x7FFFFFFF) {
                throw new IOException("Invalid block size in OPUS header: " + sizeLong);
            }
            int size = (int) sizeLong;
            if (id == AOPUSHDR) {
                if (buffer.remaining() < size) {
                    throw new IOException("Not enough data in OPUS header (invalid size: " + size + ")");
                }
                // Set the buffer to point to the OPUS header slice
                buffer.limit(buffer.position() + size);
                return;
            }

            buffer.position(buffer.position() + size);
        }

        throw new IOException("OPUS header not found");
    }
}
