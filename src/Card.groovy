import groovy.transform.Immutable
import groovy.transform.Sortable

@Immutable
@Sortable
class Card {
    String name
    @Delegate CardSet set
    boolean isFoil
    String language

    String toString() {
        "${name} ${isFoil ? '(FOIL) ' : ''}- ${set} - ${language}"
    }
}
