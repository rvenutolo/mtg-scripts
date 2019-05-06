@Grab(group = 'org.apache.commons', module = 'commons-csv', version = '1.6')

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord

if (args && args.size() != 1) {
    throw new IllegalArgumentException("Expected at most one argument: ${args}")
}

// Read MTGGoldfish collection

final InputStream inputStream = args ? new File(args[0]).newInputStream() : System.in

final CardCollection cardCollection = new CardCollection()

inputStream.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        final CardSet cardSet = new CardSet(
            setName: csvRecord.get('Set Name'),
            setCode: csvRecord.get('Set ID')
        )
        final Card card = new Card(
            name: csvRecord.get('Card'),
            set: cardSet,
            isFoil: csvRecord.get('Foil') == 'FOIL',
            language: 'EN' // MTGGoldfish does not track language, so default to English
        )
        final count = csvRecord.get('Quantity') as int
        if (count > 0) {
            // Goldfish data will have 0 quantity cards
            cardCollection.add(card, count)
        }

    }
}

// Assemble a map of card names to a list of sets in which those cards appear

final Map<String, Collection<CardSet>> cardToSets = [:].withDefault { [] }

cardCollection.cardCounts.each { final CardCount cardCount ->
    cardToSets[cardCount.name] << cardCount.set
}

// Remove all cards that appear in only one set

cardToSets.removeAll { final Map.Entry<String, Collection<CardSet>> entry ->
    entry.value.size() == 1
}

// Remove all basic lands

cardToSets.removeAll { final Map.Entry<String, Collection<CardSet>> entry ->
    entry.key in ['Forest', 'Island', 'Mountain', 'Plains', 'Swamp', 'Wastes'] || entry.key.startsWith('Snow-Covered')
}

cardToSets.sort().each { final String card, final Collection<CardSet> cardSets ->
    println("${card} - ${cardSets*.setName.sort().join(', ')}")
}
