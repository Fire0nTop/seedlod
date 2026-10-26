package dev.seedlod;

import java.util.Locale;

/**
 * Fair-play markers a server owner can put in the server's MOTD or send in any chat/system message
 * (a join message, for example), the same idea as Xaero's minimap fair-play codes:
 * <ul>
 *   <li>{@code seedlod:no-structures} - SeedLOD may draw terrain but no structures</li>
 *   <li>{@code seedlod:disable} - SeedLOD doesn't run on this server at all</li>
 * </ul>
 */
public record ServerRules(boolean noStructures, boolean disabled) {
    public static final String NO_STRUCTURES = "seedlod:no-structures";
    public static final String DISABLE = "seedlod:disable";
    public static final ServerRules NONE = new ServerRules(false, false);

    public static ServerRules parse(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean disabled = lower.contains(DISABLE);
        return new ServerRules(disabled || lower.contains(NO_STRUCTURES), disabled);
    }

    public ServerRules or(ServerRules other) {
        return new ServerRules(this.noStructures || other.noStructures, this.disabled || other.disabled);
    }

    /** True when {@code other} forbids something this doesn't. */
    public boolean isLooserThan(ServerRules other) {
        return (!this.noStructures && other.noStructures) || (!this.disabled && other.disabled);
    }
}
