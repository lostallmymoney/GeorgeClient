# AutoTool Challenges & Scenarios Roadmap

## Core Challenges

### 1. Tool Selection Problem
**Challenge**: Choosing the best tool for a block
- Different blocks have different optimal tools (pickaxe for ore, shovel for dirt, axe for wood, etc.)
- Multiple tools might work on a block
- Tools have enchantments that affect suitability (Efficiency, Fortune, Silk Touch)
- Some blocks have special requirements (ender chests MUST have silk touch if enabled)

**Scenarios**:
- Current tool is good → don't switch
- Current tool is bad, better tool exists → switch to it
- Multiple tools work equally → pick highest score
- Tool is about to break (durability low) → find alternative

---

### 2. Tool Location Problem  
**Challenge**: Tools exist in 3 locations, each has constraints
```
Hotbar (0-8):        9 slots, player directly uses, limited space
Inventory (9-35):    27 slots, need to access via swaps
Offhand:            1 slot, reserved for special items
```

**Scenarios**:
- Tool is in hotbar → direct swap, no complexity
- Tool is in inventory → need to move to hotbar (but hotbar might be full)
- Tool is in offhand → need to move to hotbar (but hotbar might be full)

---

### 3. Hotbar Capacity Problem ⚠️ **CRITICAL**
**Challenge**: Hotbar only has 9 slots. When borrowing from inventory/offhand, we displace something.

**Displacement Scenarios**:

**Scenario A: Borrowing to Empty Slot**
```
Before: Hotbar[8] = EMPTY, Inventory[15] = Shovel
Action: Move Inventory[15] → Hotbar[8]
After:  Hotbar[8] = Shovel, Inventory[15] = EMPTY
Restore: Move Hotbar[8] → Inventory[15]
```
✅ Clean - just swap back

**Scenario B: Borrowing Displaces Non-Tool**
```
Before: Hotbar[0] = Pickaxe, Inventory[15] = Shovel  
Action: Move Inventory[15] → Hotbar[0]
        (Pickaxe gets displaced)
After:  Hotbar[0] = Shovel, Inventory[15] = Pickaxe
Restore: Move Hotbar[0] → Inventory[15]  
        (Pickaxe returns)
```
✅ Clean - item that was displaced returns

**Scenario C: Borrowing Displaces Another Tool**
```
Before: Hotbar[0] = Axe, Inventory[15] = Shovel
Action: Move Inventory[15] → Hotbar[0]
        (Axe displaced to Inventory[15])
After:  Hotbar[0] = Shovel, Inventory[15] = Axe
Restore: Move Hotbar[0] → Inventory[15]
        (Axe returns)
```
✅ Clean - but ONLY if we track it properly

**Scenario D: Multiple Borrows in Sequence (THE BUG)**
```
Before:     Hotbar[0]=Axe, Hotbar[8]=EMPTY, Inv[15]=Shovel, Inv[16]=Pickaxe
Action 1:   Move Inv[15] (Shovel) → Hotbar[8]
After 1:    Hotbar[0]=Axe, Hotbar[8]=Shovel, Inv[15]=EMPTY, Inv[16]=Pickaxe
            Select Hotbar[8], mine sand with shovel...

Action 2:   Find Axe in Hotbar[0], select it
After 2:    Hotbar[0]=Axe selected, mine wood...

Action 3:   Mine sand again, need shovel
            Shovel is in Hotbar[8] OR Inv[15] (WHICH ONE??)
            If in Inv[15]: Move Inv[15] → Hotbar[X]
            
Problem: We don't know if Shovel is still in Hotbar[8] or moved to Inv[15]!
         If Axe from Hotbar[0] was swapped somewhere, tracking fails!
Restore:    Can't properly undo because we don't know the actual state
            Result: Items end up in wrong places, player can't switch back
```
❌ **This is the actual bug**

---

### 4. Durability Problem
**Challenge**: Mining changes tool durability
```
Before Mining: Pickaxe health = 100
After Mining:  Pickaxe health = 95
               (ItemStack.areEqual() will FAIL - NBT data changed!)
```

**Issue**: Can't use ItemStack.areEqual() to track items after mining changes their durability

---

### 5. Session Boundary Problem
**Challenge**: When does a mining session start/end?

**Scenarios**:
- **Single block**: Hit sand, needs shovel, mine it, done → 1 session
- **Multiple blocks same type**: Hit 3 dirt blocks in sequence → 1 session or 3?
- **Multiple blocks mixed type**: Hit sand then wood → 1 session or 2?
- **Interrupted mining**: Start mining, player stops → end session, MUST restore
- **Block becomes unbreakable**: Hit ore that's protected → cancel mining, restore
- **Player interrupted manually switches tools**: Should we track this?

**Current behavior**: Session ends when `!isBreaking()` (player releases attack key)

---

### 6. Restoration Correctness Problem
**Challenge**: Restore must be PERFECT or items get stuck in wrong places

**Requirements**:
1. Tools borrowed from inventory must return to inventory
2. Tools borrowed from offhand must return to offhand  
3. Items displaced from hotbar must return to inventory
4. Original selected slot must be restored
5. Order of restoration matters (LIFO - last borrowed must be restored first)

**Failure Modes**:
- ❌ Item stays in hotbar when it should be in inventory
- ❌ Item swapped to wrong inventory slot
- ❌ Tool borrowed but never returned (locked in use)
- ❌ Displaced item orphaned in wrong location
- ❌ Selected slot wrong, player confused

---

## State Tracking Challenge Summary

The fundamental problem: **We need to know EXACTLY what was where BEFORE mining, and EXACTLY what needs to go where AFTER mining.**

### Failed Attempt 1: Stack of Restoration Actions
```java
private Stack<RestoreAction> restoreStack
```
**Problem**: Tried to track individual borrow/return operations, but:
- Multiple sequential switches create complex state
- Hard to know which slot an item ended up in
- Durability changes break item matching logic
- Got confused after 3+ switches

### Failed Attempt 2: Hotbar Snapshot with Item Equality
```java
private ItemStack[] hotbarSnapshot
```
**Problem**: 
- Used ItemStack.areEqual() to find items after mining
- Durability changed during mining, so equality check FAILED
- Couldn't reliably locate items after they were swapped
- Restoration tried to find items that didn't match anymore

---

## The Correct Solution: Track Slot Swaps, Not Items

**Key Insight**: Don't try to match items after state changes. Instead:
1. Track EXACTLY which slots were involved in swaps
2. Remember what slot displacement happened
3. On restore, undo the EXACT swaps in reverse order

**State to Track for Each Borrow**:
```
Borrow Record {
  sourceSlot: Where the tool came from (e.g., Inventory[15])
  hotbarSlot: Which hotbar slot it went to (e.g., Hotbar[8])
  what: The actual ItemStack that was displaced (copy)
}
```

**Restoration Algorithm**:
```
For each borrow in REVERSE order:
  1. Take current item in hotbarSlot
  2. Put it back in sourceSlot
  3. Put displaced item back where it was
  4. This undoes the exact swap that happened
```

---

## All Scenarios to Handle

### ✅ Scenario 1: Single Hotbar Switch
```
State:  Hotbar[0]=Axe, Selected=0
Action: Mine wood (needs axe)
Result: Already holding right tool, no switch
Restore: Nothing to restore
```

### ✅ Scenario 2: Hotbar to Hotbar Switch  
```
State:  Hotbar[0]=Pickaxe, Hotbar[5]=Shovel, Selected=0
Action: Mine sand (needs shovel), find shovel in Hotbar[5], select it
Result: Selected=5
Restore: Select back to Hotbar[0]
```

### ✅ Scenario 3: Single Borrow from Inventory
```
State:  Hotbar=[Axe,...,Empty], Inventory[15]=Shovel, Selected=0
Action: Mine sand (needs shovel), borrow to Hotbar[8], select Hotbar[8]
Result: Hotbar[8]=Shovel, Selected=8
Restore: Move Hotbar[8] back to Inventory[15], select 0
```

### ✅ Scenario 4: Borrow Displaces Protected Item
```
State:  Hotbar[2]=Sword(protected), Inventory[15]=Shovel
Action: Mine sand, need to borrow shovel, but Hotbar[2] is protected
        Find different slot, but suppose all except [2] must be tried
        Move Inventory[15] → Hotbar[3]
Result: Hotbar[3]=Shovel, Hotbar[2] still has Sword
Restore: Move Hotbar[3] → Inventory[15]
```

### ✅ Scenario 5: Multiple Sequential Borrows (THE HARD CASE)
```
State:  Hotbar=[Axe@0, ..., Empty@8], Inv[15]=Shovel, Inv[16]=Pickaxe

Action1: Mine SAND (needs shovel)
  - Borrow Inv[15] (Shovel) → Hotbar[8]
  - Select Hotbar[8]
  - [MINING] damage Shovel durability to 85

Action2: Mine WOOD (needs axe)  
  - Axe already in Hotbar[0] and is better
  - Select Hotbar[0]
  - [MINING] damage Axe durability to 90

Action3: Mine SAND again (needs shovel)
  - Shovel still in Hotbar[8] (not moved!)
  - Select Hotbar[8]
  - [MINING] damage Shovel durability to 70

Restore (in REVERSE):
  - Pop: "Return Shovel from Hotbar[8] to Inv[15]"
    > Move Hotbar[8] → Inventory[15]
  - Select original Hotbar[0]
  
Result: Hotbar[0]=Axe(90 durability), Hotbar[8]=Empty, Inv[15]=Shovel(70 durability)
✅ CORRECT STATE
```

### ✅ Scenario 6: Borrow Displaces Non-Empty Slot
```
State:  Hotbar[7]=Bucket, Inventory[15]=Shovel

Action: Need shovel, Hotbar[7] has bucket (non-protected, non-tool)
        Move Inventory[15] → Hotbar[7]
        (Bucket displaced to Inventory[15]? Or somewhere?)
        
Problem: Where does Bucket go?
  Option A: Stays where it is (but Hotbar[7] now has Shovel - collision!)
  Option B: Pushed to Inventory[15], but Shovel came from there
  
Solution: SWAP - Inventory[15] and Hotbar[7] exchange places
  Before: Hotbar[7]=Bucket, Inventory[15]=Shovel
  After:  Hotbar[7]=Shovel, Inventory[15]=Bucket
  
Restore: SWAP back
  Before: Hotbar[7]=Shovel, Inventory[15]=Bucket
  After:  Hotbar[7]=Bucket, Inventory[15]=Shovel
```

### ❌ Scenario 7: Hotbar Full, No Protected Slots (Edge Case)
```
State:  Hotbar=[Axe, Pickaxe, Shovel, Warden, Bucket, Spade, Hoe, Saw, Torch]
        All 9 slots full, Inventory[15]=Diamond_Pickaxe
        
Problem: No empty hotbar slot to borrow to!

Solution Options:
  A) Find least useful tool and overwrite it
  B) Refuse to borrow
  C) Use a non-protected slot (current)
  
Current code: findFreeBorrowSlot() tries empty, then non-protected, then selected slot
```

### ⚠️ Scenario 8: Offhand Borrowing
```
State:  Hotbar=[...], Offhand=Shovel
Action: Need shovel, it's in offhand
        Find hotbar slot (say Hotbar[5]=Bucket)
        Move Offhand → Hotbar[5]
        (Bucket goes to Offhand)
        
Restore: Move Hotbar[5] → Offhand
        (Bucket returns to Offhand)
        
Issue: What if Offhand had protection? (unlikely but possible)
```

---

## Implementation Contract

The code MUST handle:
1. ✅ Hotbar-only selections (no borrow needed)
2. ✅ Single borrow from inventory
3. ✅ Single borrow from offhand
4. ✅ Multiple sequential switches between hotbar, inventory, offhand
5. ✅ Displacing non-empty hotbar slots
6. ✅ Respecting protected items (swords, protected list)
7. ✅ Durability anti-break (won't use broken tools)
8. ✅ Enchantment requirements (Silk Touch for ender chests, Fortune for ores if enabled)
9. ✅ Session tracking (restore when mining stops)
10. ✅ Respecting inventorySwitchBack and offHandSwitchBack settings
11. ✅ Selecting back to original slot when done
12. ✅ Not corrupting item state through multiple switches

## Test Case: User's Reported Bug

**Sequence**:
```
Initial: Hotbar[0]=Axe, others empty, Inventory has Shovel, Pickaxe

1. Mine SAND → Borrows Shovel to hotbar
   Expected: Shovel in use
   
2. Mine WOOD → Switch to Axe (already in hotbar)  
   Expected: Axe in use
   
3. Mine SAND again → Switch to Shovel
   Expected: Shovel in use (from wherever it is)
   
4. Stop mining → Restore all hotbars
   Expected: Axe back in Hotbar[0], Shovel back in inventory, nothing stuck
   Actual (bug): Some item stuck, can't switch to right tool
```

**Why it failed**:
- v3 & v4 tried to track complex state that got corrupted
- Couldn't reliably locate items after durability changes
- Restoration logic failed to put items back correctly
