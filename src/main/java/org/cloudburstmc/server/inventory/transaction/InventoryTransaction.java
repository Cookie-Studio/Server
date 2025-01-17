package org.cloudburstmc.server.inventory.transaction;

import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.api.event.inventory.InventoryClickEvent;
import org.cloudburstmc.api.inventory.Inventory;
import org.cloudburstmc.api.item.ItemStack;
import org.cloudburstmc.server.inventory.CloudPlayerInventory;
import org.cloudburstmc.server.inventory.transaction.action.InventoryAction;
import org.cloudburstmc.server.inventory.transaction.action.SlotChangeAction;
import org.cloudburstmc.server.player.CloudPlayer;

import java.util.*;
import java.util.Map.Entry;

/**
 * @author CreeperFace
 */
@Log4j2
public class InventoryTransaction {

    private long creationTime;
    protected boolean hasExecuted;

    protected CloudPlayer source;

    protected Set<Inventory> inventories = new HashSet<>();

    protected List<InventoryAction> actions = new ArrayList<>();

    public InventoryTransaction(CloudPlayer source, List<InventoryAction> actions) {
        this(source, actions, true);
    }

    public InventoryTransaction(CloudPlayer source, List<InventoryAction> actions, boolean init) {
        if (init) {
            init(source, actions);
        }
    }

    protected void init(CloudPlayer source, List<InventoryAction> actions) {
        creationTime = System.currentTimeMillis();
        this.source = source;

        for (InventoryAction action : actions) {
            this.addAction(action);
        }
    }

    public CloudPlayer getSource() {
        return source;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public Set<Inventory> getInventories() {
        return inventories;
    }

    public List<InventoryAction> getActions() {
        return actions;
    }

    public void addAction(InventoryAction action) {
        if (!this.actions.contains(action)) {
            this.actions.add(action);
            action.onAddToTransaction(this);
        } else {
            throw new RuntimeException("Tried to add the same action to a transaction twice");
        }
    }

    /**
     * This method should not be used by plugins, it's used to add tracked inventories for InventoryActions
     * involving inventories.
     *
     * @param inventory to add
     */
    public void addInventory(Inventory inventory) {
        this.inventories.add(inventory);
    }

    protected boolean matchItems(List<ItemStack> needItems, List<ItemStack> haveItems) {
        for (InventoryAction action : this.actions) {
            if (!action.getTargetItem().isNull()) {
                needItems.add(action.getTargetItem());
            }

            if (!action.isValid(this.source)) {
                return false;
            }

            if (!action.getSourceItem().isNull()) {
                haveItems.add(action.getSourceItem());
            }
        }

        var needIterator = needItems.listIterator();

        while (needIterator.hasNext()) {
            var needItem = needIterator.next();
            var haveIterator = haveItems.listIterator();

            while (haveIterator.hasNext()) {
                var haveItem = haveIterator.next();

                if (needItem.equals(haveItem)) {
                    int amount = Math.min(haveItem.getAmount(), needItem.getAmount());

                    if (haveItem.getAmount() - amount <= 0) {
                        haveIterator.remove();
                    } else {
                        haveIterator.set(haveItem.decrementAmount(amount));
                    }

                    if (needItem.getAmount() - amount <= 0) {
                        needIterator.remove();
                        break;
                    } else {
                        needItem = needItem.decrementAmount(amount);
                        needIterator.set(needItem);
                    }
                }
            }
        }

        return haveItems.isEmpty() && needItems.isEmpty();
    }

    protected void sendInventories() {
        for (Inventory inventory : this.inventories) {
            inventory.sendContents(this.source);
            if (inventory instanceof CloudPlayerInventory) {
                ((CloudPlayerInventory) inventory).sendArmorContents(this.source);
            }
        }
    }

    /**
     * Iterates over SlotChangeActions in this transaction and compacts any which refer to the same inventorySlot in the same
     * inventory so they can be correctly handled.
     * <p>
     * Under normal circumstances, the same inventorySlot would never be changed more than once in a single transaction. However,
     * due to the way things like the crafting grid are "implemented" in MCPE 1.2 (a.k.a. hacked-in), we may get
     * multiple inventorySlot changes referring to the same inventorySlot in a single transaction. These multiples are not even guaranteed
     * to be in the correct order (inventorySlot splitting in the crafting grid for example, causes the actions to be sent in the
     * wrong order), so this method also tries to chain them into order.
     * </p>
     *
     * @return successful
     */
    protected boolean squashDuplicateSlotChanges() {
        Map<Integer, List<SlotChangeAction>> slotChanges = new HashMap<>();

        for (InventoryAction action : this.actions) {
            if (action instanceof SlotChangeAction) {
                int hash = Objects.hash(((SlotChangeAction) action).getInventory(), ((SlotChangeAction) action).getSlot());

                List<SlotChangeAction> list = slotChanges.get(hash);
                if (list == null) {
                    list = new ArrayList<>();
                }

                list.add((SlotChangeAction) action);

                slotChanges.put(hash, list);
            }
        }

        for (Entry<Integer, List<SlotChangeAction>> entry : new ArrayList<>(slotChanges.entrySet())) {
            int hash = entry.getKey();
            List<SlotChangeAction> list = entry.getValue();

            if (list.size() == 1) { //No need to compact inventorySlot changes if there is only one on this inventorySlot
                slotChanges.remove(hash);
                continue;
            }

            List<SlotChangeAction> originalList = new ArrayList<>(list);

            SlotChangeAction originalAction = null;
            ItemStack lastTargetItem = null;

            for (int i = 0; i < list.size(); i++) {
                SlotChangeAction action = list.get(i);

                if (action.isValid(this.source)) {
                    originalAction = action;
                    lastTargetItem = action.getTargetItem();
                    list.remove(i);
                    break;
                }
            }

            if (originalAction == null) {
                return false; //Couldn't find any actions that had a source-item matching the current inventory inventorySlot
            }

            int sortedThisLoop;

            do {
                sortedThisLoop = 0;
                for (int i = 0; i < list.size(); i++) {
                    SlotChangeAction action = list.get(i);

                    ItemStack actionSource = action.getSourceItem();
                    if (actionSource.equals(lastTargetItem, true)) {
                        lastTargetItem = action.getTargetItem();
                        list.remove(i);
                        sortedThisLoop++;
                    } else if (actionSource.equals(lastTargetItem)) {
                        lastTargetItem = lastTargetItem.decrementAmount(actionSource.getAmount());
                        list.remove(i);
                        if (lastTargetItem.getAmount() == 0) sortedThisLoop++;
                    }
                }
            } while (sortedThisLoop > 0);

            if (list.size() > 0) { //couldn't chain all the actions together
                log.debug("Failed to compact " + originalList.size() + " actions for " + this.source.getName());
                return false;
            }

            for (SlotChangeAction action : originalList) {
                this.actions.remove(action);
            }

            this.addAction(new SlotChangeAction(originalAction.getInventory(), originalAction.getSlot(), originalAction.getSourceItem(), lastTargetItem));

            log.debug("Successfully compacted " + originalList.size() + " actions for " + this.source.getName());
        }

        return true;
    }

    public boolean canExecute() {
        this.squashDuplicateSlotChanges();

        List<ItemStack> haveItems = new ArrayList<>();
        List<ItemStack> needItems = new ArrayList<>();
        return matchItems(needItems, haveItems) && this.actions.size() > 0;
    }

    protected boolean callExecuteEvent() {
        //InventoryTransactionEvent ev = new InventoryTransactionEvent(this);
        //this.source.getServer().getEventManager().fire(ev);

        SlotChangeAction from = null;
        SlotChangeAction to = null;
        CloudPlayer who = null;

        for (InventoryAction action : this.actions) {
            if (!(action instanceof SlotChangeAction)) {
                continue;
            }
            SlotChangeAction slotChange = (SlotChangeAction) action;

            if (slotChange.getInventory() instanceof CloudPlayerInventory) {
                who = (CloudPlayer) slotChange.getInventory().getHolder();
            }

            if (from == null) {
                from = slotChange;
            } else {
                to = slotChange;
            }
        }

        if (who != null && to != null) {
            if (from.getTargetItem().getAmount() > from.getSourceItem().getAmount()) {
                from = to;
            }

            InventoryClickEvent ev2 = new InventoryClickEvent(who, from.getInventory(), from.getSlot(), from.getSourceItem(), from.getTargetItem());
            this.source.getServer().getEventManager().fire(ev2);

            if (ev2.isCancelled()) {
                return false;
            }
        }

        return false;
    }

    public boolean execute() {
        if (this.hasExecuted() || !this.canExecute()) {
            this.sendInventories();
            return false;
        }


        if (!callExecuteEvent()) {
            this.sendInventories();
            return true;
        }

        for (InventoryAction action : this.actions) {
            if (!action.onPreExecute(this.source)) {
                this.sendInventories();
                return true;
            }
        }

        for (InventoryAction action : this.actions) {
            if (action.execute(this.source)) {
                action.onExecuteSuccess(this.source);
            } else {
                action.onExecuteFail(this.source);
            }
        }

        this.hasExecuted = true;
        return true;
    }

    public boolean hasExecuted() {
        return this.hasExecuted;
    }
}
