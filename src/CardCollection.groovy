class CardCollection {

    private final Map<Card, Integer> cards = [:].withDefault { 0 }

    void add(final Card card, final int countToAdd) {
        cards[card] += countToAdd
    }

    void remove(final Card card, final int countToRemove) {
        final int currentCount = cards[card]
        if (currentCount == countToRemove) {
            cards.remove(card)
        } else if (currentCount > countToRemove) {
            cards[card] -= countToRemove
        } else {
            throw new IllegalStateException("Attempting to remove more cards than exist in collection: ${countToRemove} - ${card}")
        }
    }

    List<CardCount> removeAll(final Closure<Boolean> filter) {
        final List<CardCount> removedCards = cardCounts.findAll(filter)
        removedCards.each { remove(it.card, it.count) }
        removedCards
    }

    List<CardCount> getCardCounts() {
        cards.entrySet().collect { new CardCount(it.key, it.value) }
    }

    List<CardSet> getCardSets() {
        cards.keySet()*.set.unique()
    }

}
