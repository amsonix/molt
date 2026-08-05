package io.github.amsonix.molt.internal.reschiper.parser;

import io.github.amsonix.molt.internal.reschiper.bundle.ResourceMapping;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ResourcesMappingParserTest {

    @Test
    public void parse_apkTypeNameMappingUsesResourceMapping() throws Exception {
        Path mapping = Files.createTempFile("apk-mapping-", ".txt");
        Files.writeString(
                mapping,
                """
                res dir mapping:

                res id mapping:
                \t0x7f010001 : drawable/icon -> a1
                \t0x7f010002 : string/title -> s1

                res entries path mapping:
                \t0x7f010001 : res/drawable-hdpi/icon.png -> res/a1/a1.png
                """
        );

        ResourceMapping parsed = new ResourcesMappingParser(mapping).parse();

        Assert.assertEquals("a1", parsed.getResourceMapping().get("drawable/icon"));
        Assert.assertEquals("s1", parsed.getResourceMapping().get("string/title"));
        Assert.assertEquals(2, parsed.getResourceMapping().size());
        Assert.assertEquals(1, parsed.getEntryFilesMapping().size());
        Assert.assertEquals(
                "0x7f010001",
                readNameToId(parsed).get("drawable/icon")
        );
        mapping.toFile().delete();
    }

    @Test
    public void parse_apkMappingAllowsDuplicateObfuscatedNamesAcrossTypes() throws Exception {
        Path mapping = Files.createTempFile("apk-mapping-dup-", ".txt");
        Files.writeString(
                mapping,
                """
                res dir mapping:

                res id mapping:
                \t0x7f010001 : drawable/icon -> sfz1
                \t0x7f020001 : style/Base.Theme.MaterialComponents.CompactMenu -> sfz1

                res entries path mapping:
                """
        );

        ResourceMapping parsed = new ResourcesMappingParser(mapping).parse();

        Assert.assertEquals("sfz1", parsed.getResourceMapping().get("drawable/icon"));
        Assert.assertEquals(
                "sfz1",
                parsed.getResourceMapping().get("style/Base.Theme.MaterialComponents.CompactMenu")
        );
        Assert.assertEquals(2, parsed.getResourceMapping().size());
        mapping.toFile().delete();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readNameToId(ResourceMapping mapping) throws Exception {
        Field field = ResourceMapping.class.getDeclaredField("resourceNameToIdMapping");
        field.setAccessible(true);
        return (Map<String, String>) field.get(mapping);
    }
}
