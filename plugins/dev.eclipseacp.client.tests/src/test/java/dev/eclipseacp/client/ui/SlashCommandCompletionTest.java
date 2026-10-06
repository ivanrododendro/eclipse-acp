package dev.eclipseacp.client.ui;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SlashCommandCompletionTest {
    @Test
    public void ranksSlashCommandsByExactPrefixAndSubstringMatch() {
        Map<String, String> commands = new LinkedHashMap<>();
        commands.put("inspect", "");
        commands.put("inspect-all", "");
        commands.put("pre-inspect", "");

        assertEquals(List.of("inspect", "inspect-all", "pre-inspect"),
                SlashCommandCompletion.slashCommandMatches(commands, "inspect"));
        assertEquals(List.of("inspect", "inspect-all", "pre-inspect"),
                SlashCommandCompletion.slashCommandMatches(commands, "INSP"));
    }

}
