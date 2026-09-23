import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

final class CffFixture {
    static Path create(Path directory) throws Exception {
        return create(directory, 65);
    }

    static Path create(Path directory, int... codePoints) throws Exception {
        byte[] name = index("ProbeBold".getBytes(StandardCharsets.US_ASCII));
        byte[] charStrings = index(new byte[]{(byte)248, (byte)236, 14},
                new byte[]{(byte)248, (byte)236, 14},
                new byte[]{(byte)248, (byte)236, (byte)189, (byte)139, 21,
                        (byte)247, (byte)142, (byte)249, 80,
                        (byte)247, (byte)142, (byte)253, 80,
                        (byte)252, (byte)136, (byte)139, 5, 14});
        int offset = 4 + name.length + 11 + 2 + 2;
        byte[] topDictionary = ByteBuffer.allocate(6).put((byte)29).putInt(offset).put((byte)17).array();
        ByteArrayOutputStream cff = new ByteArrayOutputStream();
        cff.write(new byte[]{1, 0, 4, 4});
        cff.write(name);
        cff.write(index(topDictionary));
        cff.write(new byte[]{0, 0, 0, 0});
        cff.write(charStrings);

        Map<String, byte[]> tables = new TreeMap<>();
        tables.put("CFF ", cff.toByteArray());
        ByteBuffer head = ByteBuffer.allocate(54);
        head.putInt(0, 0x00010000).putInt(4, 0x00010000).putInt(12, 0x5F0F3CF5);
        head.putShort(18, (short)1000).putShort(40, (short)600).putShort(42, (short)700);
        head.putShort(46, (short)8).putShort(48, (short)2);
        tables.put("head", head.array());
        ByteBuffer hhea = ByteBuffer.allocate(36);
        hhea.putInt(0, 0x00010000).putShort(4, (short)800).putShort(6, (short)-200);
        hhea.putShort(10, (short)600).putShort(18, (short)1).putShort(34, (short)3);
        tables.put("hhea", hhea.array());
        tables.put("hmtx", ByteBuffer.allocate(12).putShort((short)600).putShort((short)0)
                .putShort((short)600).putShort((short)0).putShort((short)600).putShort((short)0).array());
        tables.put("maxp", ByteBuffer.allocate(6).putInt(0x00005000).putShort((short)3).array());
        Map<Integer, Integer> characters = new TreeMap<>();
        characters.put(32, 1);
        for (int codePoint : codePoints) characters.put(codePoint, 2);
        int cmapLength = 16 + characters.size() * 12;
        ByteBuffer cmap = ByteBuffer.allocate(12 + cmapLength);
        cmap.putShort((short)0).putShort((short)1).putShort((short)3).putShort((short)10).putInt(12);
        cmap.putShort((short)12).putShort((short)0).putInt(cmapLength).putInt(0).putInt(characters.size());
        for (var character : characters.entrySet()) {
            cmap.putInt(character.getKey()).putInt(character.getKey()).putInt(character.getValue());
        }
        tables.put("cmap", cmap.array());
        byte[] family = "ProbeBold".getBytes(StandardCharsets.UTF_16BE);
        tables.put("name", ByteBuffer.allocate(18 + family.length).putShort((short)0).putShort((short)1)
                .putShort((short)18).putShort((short)3).putShort((short)1).putShort((short)0x0409)
                .putShort((short)1).putShort((short)family.length).putShort((short)0).put(family).array());
        tables.put("post", ByteBuffer.allocate(32).putInt(0x00030000).array());
        int tableOffset = 12 + tables.size() * 16;
        int total = tableOffset + tables.values().stream().mapToInt(bytes -> (bytes.length + 3) & ~3).sum();
        ByteBuffer font = ByteBuffer.allocate(total);
        font.putInt(0x4F54544F).putShort((short)tables.size());
        int power = Integer.highestOneBit(tables.size());
        font.putShort((short)(power * 16)).putShort((short)Integer.numberOfTrailingZeros(power))
                .putShort((short)(tables.size() * 16 - power * 16));
        for (var entry : tables.entrySet()) {
            font.put(entry.getKey().getBytes(StandardCharsets.US_ASCII));
            font.putInt(checksum(entry.getValue())).putInt(tableOffset).putInt(entry.getValue().length);
            System.arraycopy(entry.getValue(), 0, font.array(), tableOffset, entry.getValue().length);
            tableOffset += (entry.getValue().length + 3) & ~3;
        }
        Path destination = directory.resolve("fixture-cff-bold.otf");
        Files.write(destination, font.array());
        return destination;
    }

    private static byte[] index(byte[]... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        output.write(entries.length);
        output.write(1);
        int offset = 1;
        output.write(offset);
        for (byte[] entry : entries) {
            offset += entry.length;
            output.write(offset);
        }
        for (byte[] entry : entries) {
            output.write(entry);
        }
        return output.toByteArray();
    }

    private static int checksum(byte[] bytes) {
        byte[] padded = java.util.Arrays.copyOf(bytes, (bytes.length + 3) & ~3);
        ByteBuffer buffer = ByteBuffer.wrap(padded);
        int result = 0;
        while (buffer.hasRemaining()) {
            result += buffer.getInt();
        }
        return result;
    }
}
