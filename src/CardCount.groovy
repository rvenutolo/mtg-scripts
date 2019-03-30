import groovy.transform.Immutable
import groovy.transform.Sortable

@Immutable
@Sortable
class CardCount {
    @Delegate Card card
    int count

    String toString() {
        "${card} - ${count}"
    }
}
