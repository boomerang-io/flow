// FilterableMultiSelectProps declares filterItems/compareItems/sortItems as required, even though
// the component supplies working defaults at runtime. These are equivalents mirroring Carbon's own
// default filter/sort behaviour, shared by every filter bar that uses the control.

export function filterItemsByLabel<Item>(
  items: readonly Item[],
  { itemToString, inputValue }: { itemToString: (item: Item) => string; inputValue: string | null },
): Item[] {
  if (!inputValue) {
    return items.slice();
  }
  return items.filter((item) => itemToString(item).toLowerCase().includes(inputValue.toLowerCase()));
}

function compareItemLabels(labelA: string, labelB: string, { locale }: { locale: string }): number {
  return labelA.localeCompare(labelB, locale, { numeric: true });
}

// Carbon types compareItems/sortItems' compareItems against the raw item (not its rendered
// label), so adapt the label comparator to that item-based contract.
export function makeCompareItems<Item>(itemToString: (item: Item) => string) {
  return (itemA: Item, itemB: Item, options: { locale: string }): number =>
    compareItemLabels(itemToString(itemA), itemToString(itemB), options);
}

export function sortItemsBySelection<Item>(
  items: Item[],
  {
    selectedItems = [],
    compareItems,
    locale = "en",
  }: {
    selectedItems?: Item[];
    itemToString?: (item: Item) => string;
    compareItems: (a: Item, b: Item, ctx: { locale: string }) => number;
    locale?: string;
  },
): Item[] {
  return [...items].sort((itemA, itemB) => {
    const hasItemA = selectedItems.includes(itemA);
    const hasItemB = selectedItems.includes(itemB);
    if (hasItemA && !hasItemB) return -1;
    if (hasItemB && !hasItemA) return 1;
    return compareItems(itemA, itemB, { locale });
  });
}
