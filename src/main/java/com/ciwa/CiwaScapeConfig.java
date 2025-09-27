package com.ciwa;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("Ciwascape")
public interface CiwaScapeConfig extends Config
{
	@ConfigItem(
			keyName = "resetQuest",
			name = "Reset all quests now",
			description = "Toggle on to reset; will flip off automatically."
	)
	default boolean resetQuest() { return false; }

	@ConfigItem(
			keyName = "enableInquire",
			name = "Enable Inquire",
			description = "Adds an 'Inquire' right-click option on NPCs relevant to active quests."
	)
	default boolean enableInquire() { return true; }

	@ConfigItem(
			keyName = "echoToChat",
			name = "Echo dialogue to chat",
			description = "Mirror overhead dialogue to chat when it appears."
	)
	default boolean echoToChat() { return true; }
}
