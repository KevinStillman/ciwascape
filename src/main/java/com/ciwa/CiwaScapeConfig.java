package com.ciwa;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("Ciwascape")
public interface CiwaScapeConfig extends Config
{
	@ConfigItem(
			keyName = "resetQuest",
			name = "Reset quest now",
			description = "Toggle on to reset; it will flip back off automatically."
	)
	default boolean resetQuest()
	{
		return false;
	}
}
