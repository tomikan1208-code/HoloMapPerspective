package dev.openai.holomap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Metadata for an indexed, GPU-ready animation with independently compressed poses. */
final class PackedAnimation {
    private static final int HEADER_BYTES = 52;
    private static final int FRAME_ENTRY_BYTES = 16;

    record Info(ObjBinaryCache.Frame mesh, int frameCount, float fps) {}

    static Info inspect(Path path, int maxFrames, int maxTriangles) throws IOException {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining() && file.read(header) != -1) {}
            if (header.hasRemaining()) throw invalid(path);
            header.flip();
            byte[] magic = new byte[8];
            header.get(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("HMAPANI1")) throw invalid(path);

            int triangles = header.getInt();
            int vertices = header.getInt();
            int frameCount = header.getInt();
            float fps = header.getFloat();
            float[] bounds = new float[6];
            for (int i = 0; i < bounds.length; i++) bounds[i] = header.getFloat();
            int manifestBytes = header.getInt();
            if (frameCount < 1 || frameCount > maxFrames)
                throw new IOException("Packed animation frame count must be 1.." + maxFrames);
            if (triangles > maxTriangles)
                throw new IOException("Packed animation exceeds max-triangles: " + triangles);
            if (triangles < 1 || vertices < 1 || vertices > 3L * triangles || !Float.isFinite(fps) || fps <= 0
                    || manifestBytes < 1 || manifestBytes > 1_048_576) throw invalid(path);
            for (int i = 0; i < 3; i++) {
                if (!Float.isFinite(bounds[i]) || !Float.isFinite(bounds[i + 3]) || bounds[i] > bounds[i + 3])
                    throw invalid(path);
            }

            long frameTable = HEADER_BYTES + manifestBytes + 12L * vertices + 12L * triangles;
            long fileSize = file.size();
            long remaining = fileSize - frameTable - (long) FRAME_ENTRY_BYTES * frameCount;
            if (remaining < frameCount) throw invalid(path);
            file.position(frameTable);
            try (DataInputStream table = new DataInputStream(new BufferedInputStream(Channels.newInputStream(file)))) {
                for (int i = 0; i < frameCount; i++) {
                    int compressedBytes = Integer.reverseBytes(table.readInt());
                    if (compressedBytes < 1 || compressedBytes > 12L * vertices + 64 || compressedBytes > remaining)
                        throw invalid(path);
                    remaining -= compressedBytes;
                    table.skipNBytes(12);
                }
            }
            if (remaining != 0) throw invalid(path);
            return new Info(new ObjBinaryCache.Frame(path, triangles, bounds), frameCount, fps);
        }
    }

    private static IOException invalid(Path path) {
        return new IOException("Invalid or incomplete packed animation: " + path);
    }
}
