<img src="src\main\resources\assets\adminscope\icon.png" alt="AdminScope" width=128 height=128/>

AdminScope Mod (Fabric/Forge)
============================

[![Get it on Modrinth](https://img.shields.io/badge/Get%20it%20on-Modrinth-black?style=for-the-badge&logo=modrinth&logoColor=00AF5C)](https://modrinth.com/project/W8fUtxyA)
<a href="https://www.curseforge.com/minecraft/mc-mods/adminscope">
  <img
    alt="Get it on CurseForge"
    src="https://img.shields.io/badge/Get%20it%20on-CurseForge-f16436?style=for-the-badge&logo=curseforge&logoColor=white"
  />
</a>



The **AdminScope Mod** is a server-side moderation tool that lets authorized staff spectate a player using the target player's POV, then instantly return to their exact position and gamemode. It is built for survival servers and avoids freecam abuse by enforcing a configurable distance limit.




----------------------------------------------------------------

Key Features
------------

*   **Direct spectate command**: `/spectate <player>` immediately switches you to the target player's POV.
*   **Quick POV reset**: Use `/spectate pov` or click the return-to-POV hint to get back to the target player's view from freecam.
*   **Accurate restore**: Position, gamemode, and dimension are restored when spectate ends.
*   **Freecam distance limit**: Configurable boundary (default 30 blocks) with automatic snap-back and warnings.
*   **Cooldown**: Configurable cooldown between spectate sessions (default 30 seconds).
*   **Combat safety**: Optional block when in combat or near hostile mobs.
*   **Player-only POV**: Camera is forced back to the target player if it switches to mobs or other entities.
*   **Disconnect handling**: Spectating ends automatically if the target leaves the server.
*   **Permission revocation handling**: An active session ends and restores the spectator if they lose permission.
*   **Spectate chain handling**: You cannot target someone already spectating; if someone you are watching starts a session, your session ends first.
*   **Jail Mod compatibility**: Jailed players cannot spectate.
*   **Crash recovery**: Spectate sessions are persisted to disk.
*   **Auto-patching config**: New config fields are added automatically on update.
*   **Optional Forge inventory viewer**: Forge servers can use `/invsee` to inspect or edit a target's live inventory, ender chest, and (when Traveler's Backpack is installed) worn backpack storage.

How to use:
-----------

1.  Install the loader-specific mod jar and its loader API on the server.
2.  Start the server once to generate configuration files.
3.  Run `/spectate <player>` to start spectating.
4.  Use `/spectate pov` or the clickable POV hint to return to the target's POV from freecam.
5.  Use `/spectate stop` to return to your saved position, or click the stop hint in chat.
6.  Use `/spectate reload` after editing the config.

Requirements
------------

*   Minecraft 1.20.1
*   Fabric Loader + Fabric API, or Forge 47.4.x
*   Java 17 or newer (Java 17 is recommended for 1.20.1)

Installation
------------

1.  Place the matching jar from `fabric/build/libs` or `forge/build/libs` in the server `mods` folder.
2.  Start the server to generate configuration files.

The release jars use the format
`AdminScope-<version>+<modloader>-1.20.1.jar`, for example
`AdminScope-0.7.3-Alpha+fabric-1.20.1.jar` or
`AdminScope-0.7.3-Alpha+forge-1.20.1.jar`.

For the client configuration screen, install Mod Menu and Cloth Config on
Fabric, or Cloth Config Forge on Forge. The screen is available from the
loader's Mods menu.

The Fabric and Forge projects are independent. Build from either platform folder
with `gradlew build`, or use the root `gradlew buildAll` task. Shared gameplay
code is kept in the root `src/main/java`; loader-specific entrypoints and
integrations live under `fabric/` and `forge/`.

Download
----------

The mod is available on:
* **Modrinth**: https://modrinth.com/project/W8fUtxyA
* **CurseForge**: https://www.curseforge.com/minecraft/mc-mods/adminscope
* **GitHub**: https://github.com/Abdo9616/AdminScope-mod


Available Commands
------------------

### 1. `/spectate <player>`

*   **Description**: Spectate a specific player immediately.
*   **Who can use it**: Server operators and players with a role/tag listed in `admin_roles`.
*   **Syntax**: `/spectate <player_name>`
*   **Example**: `/spectate Steve`

### 2. `/spectate stop`

*   **Description**: Stop spectating and return to your original position.
*   **Who can use it**: Server operators and players with a role/tag listed in `admin_roles`.
*   **Example**: `/spectate stop`

### 3. `/spectate pov`

*   **Description**: Return to the target player's POV while in freecam. The same action is available through the clickable hint shown when a session starts.
*   **Who can use it**: Authorized players with an active spectate session.
*   **Example**: `/spectate pov`

### 4. `/spectate reload`

*   **Description**: Reloads the configuration without restarting the server.
*   **Who can use it**: Server operators and players with a role/tag listed in `admin_roles`.
*   **Example**: `/spectate reload`

Spectate Rules
--------------

While spectating, the following rules apply:

*   You can only use the target player's POV (no mob POV).
*   Freecam distance is limited by `freecam_distance_limit`.
*   If you push the limit repeatedly, you are snapped back to the target POV.
*   If the target leaves the server, spectating ends automatically.
*   If you lose permission to use spectating, your session ends and your saved state is restored.
*   A player already in a spectate session cannot be selected as a target.
*   If someone you are spectating starts a spectate session, your session ends before theirs starts.

Jail Mod Compatibility
----------------------

If Jail Mod is installed, jailed players cannot use `/spectate`.

Configuration file
------------------

### `config/spectatemod/config.json`

This file is automatically generated and updated. Here are the options you can find:

*   **`admin_roles`**: Roles/tags that grant /spectate access. You can use either `"op,admin,mod"` or `["op", "admin", "mod"]`. Use `"op"` to include operators.
*   **`spectate_cooldown`**: Cooldown in seconds between spectate sessions.
*   **`prevent_combat_spectate`**: If true, prevents spectating while in combat or near hostile mobs.
*   **`combat_check_radius`**: Radius in blocks to check for hostile mobs.
*   **`save_spectate_positions`**: If true, saves spectate state to disk for crash recovery.
*   **`freecam_distance_limit`**: Max distance (in blocks) from the target player while in freecam. Use `0` to disable.

#### Example configuration:
```json
{
  "_config_guide": "SpectateMod Configuration Guide: \n- admin_roles: Roles/tags that grant /spectate access. Supports either 'op,admin,mod' or ['op','admin','mod'].\n- spectate_cooldown: Cooldown time in seconds before an admin can spectate again after stopping.\n- prevent_combat_spectate: If true, prevents spectating when the admin is in combat or near hostile mobs.\n- combat_check_radius: Radius in blocks to check for hostile mobs when prevent_combat_spectate is enabled.\n- save_spectate_positions: If true, saves spectate positions to disk to prevent data loss on crashes.\n- freecam_distance_limit: Max distance (in blocks) you can move away from the spectated player while in freecam.",
  "admin_roles": "op",
  "spectate_cooldown": 30,
  "prevent_combat_spectate": true,
  "combat_check_radius": 16.0,
  "save_spectate_positions": true,
  "freecam_distance_limit": 30.0
}
```

Data persistence
----------------

Active spectate sessions are saved to:

`config/spectatemod/spectate_data.json`

This allows the server to restore admins to their correct position after crashes or restarts (when enabled in config).

Usage Tips
----------

Use the `/spectate reload` command after changing configuration. If you use custom roles, add tags like:

```bash
/tag @p add admin
```

Forge `/invsee`
---------------

The Forge build registers `/invsee <player>` and `/invsee` (the current spectate target). It uses vanilla generic chest menus, so the inspected player does not need a client mod. Operators receive these default permissions through Forge's Permission API: `adminscope.spectate` (level 2), `adminscope.invsee.view` (level 2), `adminscope.invsee.edit` (level 3), `adminscope.invsee.enderchest` (level 2), and `adminscope.invsee.backpack` (level 2). A permission manager such as LuckPerms may grant or override these nodes. Players matching the shared `admin_roles` entries in `config/spectatemod/config.json` also receive all invsee permissions.

Forge generates `config/spectatemod/invsee.json` in UTF-8. It contains `enabled`, `allowSelfView`, `logStaffActions`, and a `menuButtons` array. Existing button arrays are preserved; new defaults are only written when the key is absent. `/spectate reload` reloads this file and closes open viewer menus. If Traveler's Backpack is not installed, the backpack button is omitted. If it is installed, the Forge server and every client using the native backpack viewer must have the matching Traveler's Backpack 1.20.1 release; AdminScope reuses its native menu, permits item interactions for viewers with `adminscope.invsee.edit` (or a configured admin role), and blocks only Traveler's settings/gear action during invsee.
