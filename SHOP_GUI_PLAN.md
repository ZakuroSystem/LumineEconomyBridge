# Shop GUI Implementation Plan

The goal is to replace command driven shop management with an inventory
based interface. Below is the staged approach to shipping the feature.

## Phase 1 – Data plumbing
- [ ] Add backend API endpoints that expose the player-owned shop list and
      individual shop configurations required by the GUI.
- [ ] Introduce payload serializers on the plugin side that transform the
      HTTP responses into the existing `ShopItem` / shop metadata objects.
- [ ] Build a caching layer to minimise network round-trips while editing.

## Phase 2 – Navigation shell
- [ ] Implement the main menu layout with buttons for "My Shops", "Create
      Shop", and quick actions for currency/tax settings.
- [ ] Create paginated sub-menus for the shop list and placeholder actions
      for editor entry points.
- [ ] Wire the `/le shop` (or dedicated) command to open the inventory GUI.

## Phase 3 – Shop editor workflows
- [ ] Provide a detailed editor screen for each shop that supports adding,
      removing, and rearranging `ShopItem` entries.
- [ ] Offer price input via incremental buttons and chat-based numeric
      prompts for precise values (honouring the configured decimal places).
- [ ] Surface stock controls, tax toggles, hopper bindings, and currency
      selection in dedicated sub views.

## Phase 4 – Quality of life
- [ ] Persist partially completed changes with explicit "Save" and
      "Discard" actions.
- [ ] Add audit logging and confirmation dialogues for destructive actions.
- [ ] Integrate contextual help tooltips that reference `SHOP_HELP.md` and
      guide users through the interface.

Progress will be tracked in this document as the feature evolves.
