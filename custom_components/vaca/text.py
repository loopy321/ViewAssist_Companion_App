"""Text entities for VACA integration."""

from __future__ import annotations

from typing import TYPE_CHECKING

from homeassistant.components.text import TextEntity, TextEntityDescription
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers import restore_state
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import DOMAIN
from .devices import VASatelliteDevice
from .entity import VASatelliteEntity

if TYPE_CHECKING:
    from homeassistant.components.wyoming import DomainDataItem


async def async_setup_entry(
    hass: HomeAssistant,
    config_entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up text entities."""
    item: DomainDataItem = hass.data[DOMAIN][config_entry.entry_id]
    device: VASatelliteDevice = item.device  # type: ignore[assignment]

    # Setup is only forwarded for satellites
    assert device is not None

    async_add_entities([WyomingSatelliteScreensaverDashboardText(device)])


class WyomingSatelliteScreensaverDashboardText(
    VASatelliteEntity, TextEntity, restore_state.RestoreEntity
):
    """Entity to configure screensaver dashboard path."""

    entity_description = TextEntityDescription(
        key="screensaver_dashboard",
        translation_key="screensaver_dashboard",
        entity_category=EntityCategory.CONFIG,
    )

    _attr_native_min = 0
    _attr_native_max = 128
    _attr_mode = "text"

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            await self.async_set_value(state.state or "")
            return

        await self.async_set_value("/dashboard-screensaver")

    async def async_set_value(self, value: str) -> None:
        """Update the value."""
        clean = value.strip()
        if clean and not clean.startswith("/"):
            clean = f"/{clean}"

        self._attr_native_value = clean
        self.async_write_ha_state()
        self._device.set_custom_setting(self.entity_description.key, clean)
