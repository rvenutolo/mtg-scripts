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
        cardCollection.add(card, count)
    }
}
