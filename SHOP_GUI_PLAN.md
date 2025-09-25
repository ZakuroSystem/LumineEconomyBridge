# Shop GUI Implementation Plan

The goal is to replace command driven shop management with an inventory
based interface. Below is the staged approach to shipping the feature.

## Phase 1 – Data plumbing
- [x] Add backend API endpoints that expose the player-owned shop list and
      individual shop configurations required by the GUI.
- [x] Introduce payload serializers on the plugin side that transform the
      HTTP responses into the existing `ShopItem` / shop metadata objects.
- [x] Build a caching layer to minimise network round-trips while editing.

## Phase 2 – Navigation shell
- [x] Implement the main menu layout with buttons for "My Shops", "Create
      Shop", and quick actions for editor entry points.
- [x] Create paginated sub-menus for the shop list and placeholder actions
      for editor entry points.
- [x] Wire the `/le shop` (or dedicated) command to open the inventory GUI.

## Phase 3 – Shop editor workflows
- [x] Provide a detailed editor screen for each shop that supports adding,
      removing, and rearranging `ShopItem` entries.
- [x] Offer price input via incremental buttons and chat-based numeric
      prompts for precise values (honouring the configured decimal places).
- [x] Surface stock controls, tax toggles, hopper bindings, and currency
      selection in dedicated sub views.

## Phase 4 – Quality of life
- [x] Present explicit enable/save/cancel controls for autoprice editing so
      players can review drafts before committing them.
- [x] Provide confirmation and feedback messaging for destructive actions
      such as disabling autoprice or aborting prompts.
- [x] Align button lore and documentation with `SHOP_HELP.md` so players have
      contextual guidance while navigating the GUI.

The GUI, command wiring, and documentation are now complete and available in-game.
