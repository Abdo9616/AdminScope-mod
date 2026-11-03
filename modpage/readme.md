AdminScope Mod (Fabric/Forge)
============================

[![Get it on Modrinth](https://img.shields.io/badge/Get%20it%20on-Modrinth-black?style=for-the-badge&logo=modrinth&logoColor=00AF5C)](https://modrinth.com/project/W8fUtxyA)
<a href="https://www.curseforge.com/minecraft/mc-mods/adminscope">
  <img
    alt="Get it on CurseForge"
    src="https://img.shields.io/badge/Get%20it%20on-CurseForge-f16436?style=for-the-badge&logo=curseforge&logoColor=white"
  />
</a>



The **AdminScope Mod** is a server-side (and singleplayer) moderation tool that lets authorized staff spectate a player using the target player's POV, then instantly return to their exact position and gamemode. It is built for survival servers and avoids freecam abuse by enforcing a configurable distance limit.




----------------------------------------------------------------

Key Features
------------

*   **Direct spectate command**: `/spectate <player>` immediately switches you to the target player's POV.
*   **Quick POV reset**: Use `/spectate pov` or click the return-to-POV hint to get back to the target player's view from freecam.
*   **Accurate restore**: Position, gamemode, and dimension are restored when spectate ends.
*   **Freecam distance limit**: Configurable boundary (default 30 blocks) with automatic snap-back and warnings.
*   **Cooldown**: Configurable cooldown between spectate sessions (default 30 seconds).
*   **Combat safety**: Optional block when in combat or near hostile mobs to prevent abuse.
*   **Player-only POV**: Camera is forced back to the target player if it switches to mobs or other entities.
*   **Disconnect handling**: Spectating ends automatically if the target leaves the server.
*   **Permission revocation handling**: An active session ends and restores the spectator if they lose permission.
*   **Spectate chain handling**: You cannot target someone already spectating; if someone you are watching starts a session, your session ends first.
*   **Jail Mod compatibility**: Jailed players cannot spectate.
*   **Crash recovery**: Spectate sessions are persisted to disk.
*   **Auto-patching config**: New config fields are added automatically on update.

How to use:
-----------

1.  Install the mod and Fabric API on the server.
2.  Start the server once to generate configuration files.
3.  Run `/spectate <player>` to start spectating.
4.  Use `/spectate pov` or the clickable POV hint to return to the target's POV from freecam.
5.  Use `/spectate stop` to return to your saved position, or click the stop hint in chat.
6.  Use `/spectate reload` after editing the config.

Requirements
------------

*   Fabric/Forge Loader
*   Fabric API
*   Optional: Cloth Config API (If installed on client, Not required on server)
*   Optional: Mod Menu for config and update checking for fabric (If installed on client, Not required on server)
*   Optional: LuckPerms for permission checks (Forge only for now)
*   optional: Traveler's Backpack for inventory viewing (Forge only for now)

Compatibility
-------------
*   Read the compatibility section on the [wiki](https://github.com/Abdo9616/AdminScope-mod/wiki#compatibility) for more details.

Installation
------------

1.  Place the mod's `.jar` file in the server/client `mods` folder.
2.  Start the server to generate configuration files.


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


Configuration file
------------------

*   Read the configuration section on the [wiki](https://github.com/Abdo9616/AdminScope-mod/wiki#default-configuration) for more details.
