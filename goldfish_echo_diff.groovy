#!/usr/bin/env groovy

@Grab(group = 'org.apache.commons', module = 'commons-csv', version = '1.6')

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord

if (args?.size() != 2) {
    throw new IllegalArgumentException("Expected two arguments")
}

final File goldfishFile = new File(args[0])
final File echoFile = new File(args[1])

// MTGGoldfish and EchoMTG do not use all the same set names and codes
// Read data to convert MTGGoldfish set names and codes to those used by EchoMTG

final Map<CardSet, CardSet> goldfishToEchoSets = [:]

new File('goldfish_to_echo_sets.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader(
        'Goldfish Name', 'Goldfish Code', 'Echo Name', 'Echo Code'
    ).parse(reader).each { final CSVRecord csvRecord ->
        final CardSet goldfishSet = new CardSet(
            setName: csvRecord.get('Goldfish Name'),
            setCode: csvRecord.get('Goldfish Code')
        )
        final CardSet echoSet = new CardSet(
            setName: csvRecord.get('Echo Name'),
            setCode: csvRecord.get('Echo Code')
        )
        goldfishToEchoSets[goldfishSet] = echoSet
    }
}

// Read the EchoMTG set data to use in validation of set names and codes

final Set<CardSet> echoSets = []
final Map<String, String> echoSetCodeToName = [:].withDefault { '' }
final Map<String, String> echoSetNameToCode = [:].withDefault { '' }

new File('echo_sets.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Code').parse(reader).each { final CSVRecord csvRecord ->
        final CardSet cardSet = new CardSet(
            setName: csvRecord.get('Name'),
            setCode: csvRecord.get('Code')
        )
        echoSets << cardSet
        echoSetCodeToName[cardSet.setCode] = cardSet.setName
        echoSetNameToCode[cardSet.setName] = cardSet.setCode
    }
}

// Read MTGGoldfish collection data

final CardCollection goldfishCollection = new CardCollection()

goldfishFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        final CardSet goldfishSet = new CardSet(
            setName: csvRecord.get('Set Name'),
            setCode: csvRecord.get('Set ID')
        )
        final Card card = new Card(
            name: csvRecord.get('Card'),
            set: goldfishToEchoSets[goldfishSet] ?: goldfishSet,
            isFoil: csvRecord.get('Foil') == 'FOIL',
            language: 'EN' // MTGGoldfish does not track language, so default to English
        )
        final count = csvRecord.get('Quantity') as int
        goldfishCollection.add(card, count)
    }
}

// Read EchoMTG collection data

final CardCollection echoCollection = new CardCollection()

final Closure cleanEchoCardName = { final String inputCardName ->
    // Some cards in Echo start with 'AE' like 'AEther Vial'
    // Standardize on 'Aether'
    final String cleanedCardName = inputCardName.replace('AEther', 'Aether')
    cleanedCardName
}

final Closure cleanEchoSetName = { final String inputSetName ->
    // Some set names have trailing spaces
    String cleanedSetName = inputSetName.trim()
    // For 'Buy-A-Box' -> 'Buy-a-box'
    cleanedSetName = cleanedSetName.replace('-A-', '-a-')
    if (cleanedSetName == cleanedSetName.toUpperCase()) {
        // Some set names are all uppercase, covert to lowercase then capitalize
        cleanedSetName = cleanedSetName.toLowerCase().split(' ').collect {
            it in ['of', 'the'] ? it : it.capitalize()
        }.join(' ')
    }
    cleanedSetName
}

echoFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        final int nonFoilCount = csvRecord.get('Count') as int
        final int foilCount = csvRecord.get('Foil') as int
        final String name = cleanEchoCardName(csvRecord.get('Name'))
        final String setName = cleanEchoSetName(csvRecord.get('Edition'))
        final String setCode = echoSetNameToCode[setName]
        final CardSet cardSet = new CardSet(
            setName: setName,
            setCode: setCode
        )
        final String language = csvRecord.get('Language')
        if (name == 'Azorius Guildgate') {
            // These are missing the set name column entirely, including delimiter (wtf?)
            final CardSet rnaCardSet = new CardSet(
                setCode: 'RNA',
                setName: 'Ravnica Allegiance'
            )
            // Currently I only have English non-foil
            // The columns after the foil count column are all off, so just hardcode in values
            final Card azoriusGuildgateCard = new Card(
                name: 'Azorius Guildgate',
                set: rnaCardSet,
                isFoil: false,
                language: 'EN'
            )
            echoCollection.add(azoriusGuildgateCard, nonFoilCount)
            return
        }
        if (nonFoilCount) {
            final Card nonFoilCard = new Card(
                name: name,
                set: cardSet,
                isFoil: false,
                language: language
            )
            echoCollection.add(nonFoilCard, nonFoilCount)
        }
        if (foilCount) {
            final Card foilCard = new Card(
                name: name,
                set: cardSet,
                isFoil: false,
                language: language
            )
            echoCollection.add(foilCard, foilCount)
        }
    }
}

// Go through collections to find set names and codes not in EchoMTG and fail if there are any

final List<CardSet> badGoldfishCardSets = goldfishCollection.cardSets - echoSets
if (badGoldfishCardSets) {
    throw new IllegalArgumentException(
        "MTGGoldfish card sets not in master EchoMTG set data:\n${badGoldfishCardSets.sort().join('\n')}"
    )
}

final List<CardSet> badEchoCardSets = echoCollection.cardSets - echoSets
if (badEchoCardSets) {
    throw new IllegalArgumentException(
        "EchoMTG card sets not in master EchoMTG set data:\n${badEchoCardSets.sort().join('\n')}"
    )
}

// Basic lands, aside from those from Unstable, were not included in import from MTGGoldfish into EchoMTG,
// so remove them

goldfishCollection.removeAll { final CardCount cardCount ->
    cardCount.name in ['Forest', 'Island', 'Mountain', 'Plains', 'Swamp', 'Wastes'] && cardCount.setCode != 'UN3'
}

// There are some cards I did not import from MTGGoldfish to EchoMTG
// Read and remove those cards

final List<CardCount> skippedCards = []

new File('skip_in_echo_import.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Set Code', 'Count').parse(reader).each { final CSVRecord csvRecord ->
        final CardSet cardSet = new CardSet(
            setCode: csvRecord.get('Set Code'),
            setName: echoSetCodeToName[csvRecord.get('Set Code')]
        )
        final Card card = new Card(
            name: csvRecord.get('Name'),
            set: cardSet,
            isFoil: false, // currently don't have any foils to skip
            language: 'EN' // currently dont' have any non-English to skip
        )
        final int count = csvRecord.get('Count') as int
        final CardCount cardCount = new CardCount(
            card: card,
            count: count
        )
        skippedCards << cardCount
    }
}

skippedCards.each { final CardCount cardCount ->
    goldfishCollection.remove(cardCount.card, cardCount.count)
}

// Print diff info

println('Cards in MTGGoldfish not in EchoMTG')
println('-' * 80)
goldfishCollection.cardCounts.findAll { final CardCount goldfishCardCount ->
    goldfishCardCount.count > echoCollection.getCardCount(goldfishCardCount.card)
}.collect { final CardCount goldfishCardCount ->
    new CardCount(
        card: goldfishCardCount.card,
        count: goldfishCardCount.count - echoCollection.getCardCount(goldfishCardCount.card)
    )
}.sort().each { final CardCount diffCardCount ->
    println(diffCardCount)
}
