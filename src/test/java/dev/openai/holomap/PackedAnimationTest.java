package dev.openai.holomap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

class PackedAnimationTest {
    @TempDir Path directory;

    @Test
    void packedClipUsesItsSampleRateAndLoops() throws IOException {
        Path path = writeClip();
        PackedAnimation.Info info = PackedAnimation.inspect(path, 5, 1);
        assertEquals(1, info.mesh().triangles());
        assertEquals(5, info.frameCount());
        assertEquals(32, info.fps());
        Models.Asset asset = new Models.Asset(List.of(info.mesh()), 0, 0, 0, 1, 1, 1, 0, info);
        assertEquals(new Models.Sample(path, 2), asset.atSeconds(2.0 / 32, 1));
        assertEquals(new Models.Sample(path, 0), asset.atSeconds(5.0 / 32, 1));
        assertEquals(new Models.Sample(path, 1), asset.atTick(1, 1));
    }

    @Test
    void rejectsFrameLimitAndTruncatedPose() throws IOException {
        Path path = writeClip();
        assertThrows(IOException.class, () -> PackedAnimation.inspect(path, 4, 1));
        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PackedAnimation.inspect(path, 5, 1));
    }

    private Path writeClip() throws IOException {
        byte[] manifest = "{\"segments\":[{\"first\":0,\"count\":3}]}".getBytes(US_ASCII);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(compressed)) {
            zlib.write(new byte[18]);
        }
        byte[] pose = compressed.toByteArray();
        ByteBuffer bytes = ByteBuffer.allocate(52 + manifest.length + 36 + 12 + 5 * (16 + pose.length))
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("HMAPANI1".getBytes(US_ASCII)).putInt(1).putInt(3).putInt(5).putFloat(32);
        for (float bound : new float[]{-1, -1, 0, 1, 1, 0}) bytes.putFloat(bound);
        bytes.putInt(manifest.length).put(manifest).put(new byte[36]).putInt(0).putInt(1).putInt(2);
        for (int i = 0; i < 5; i++) bytes.putInt(pose.length).put(new byte[12]);
        for (int i = 0; i < 5; i++) bytes.put(pose);
        return Files.write(directory.resolve("model.hanim"), bytes.array());
    }
}
