class CardCollection {

    private final Map<Card, Integer> cards = [:].withDefault { 0 }

    void addCards(final Card card, final int countToAdd) {
        cards[card] += countToAdd
    }

    void removeCards(final Card card, final int countToRemove) {
        final int currentCount = cards[card]
        if (currentCount == countToRemove) {
            cards.remove(card)
        } else if (currentCount > countToRemove) {
            cards[card] -= countToRemove
        } else {
            throw new IllegalStateException("Attempting to remove more cards than exist in collection: ${countToRemove} - ${card}")
        }
    }

    List<CardCount> getCardCounts() {
        cards.entrySet().collect { new CardCount(it.key, it.value) }.sort()
    }

    List<CardSet> getCardSets() {
        cards.keySet()*.set.unique().sort()
    }

}
