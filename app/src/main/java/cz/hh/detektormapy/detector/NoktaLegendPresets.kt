package cz.hh.detektormapy.detector

import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain

/**
 * Ready-made presets for the Nokta The Legend, as used in Podkrkonoší.
 *
 * These are the owner's own working settings, transcribed from `docs/nokta-legend-presety.md`
 * — not factory presets and not something the app invented. That distinction is the whole
 * reason the advisor exists in this shape: it recommends settings somebody actually trusts on
 * a specific machine, instead of generic advice that fits no detector in particular.
 *
 * Sensitivity, ground balance and recovery still have to be trimmed on the spot, which is what
 * each preset's [tuning] section is for.
 */
data class SeedPreset(
    val name: String,
    val terrain: Terrain,
    val soil: SoilCondition,
    val sensitivity: String,
    val groundBalance: String,
    val discrimination: String,
    val useCase: String,
    val settings: String,
    val why: String,
    val tuning: String,
) {
    /** Everything that does not fit the dedicated columns, laid out for the detail screen. */
    fun toNotes(): String = buildString {
        appendLine(useCase)
        appendLine()
        appendLine("NASTAVENÍ")
        appendLine(settings)
        appendLine()
        appendLine("PROČ")
        appendLine(why)
        appendLine()
        appendLine("DOLADĚNÍ V TERÉNU")
        append(tuning)
    }
}

object NoktaLegendPresets {

    const val DETECTOR_NAME = "Nokta The Legend"
    const val BRAND = "Nokta"
    const val MODEL = "The Legend (V1.17)"
    const val COIL = "LG30 / standardní střední cívka"

    /** Shown on the detector so it is obvious where the numbers came from. */
    const val DETECTOR_NOTES =
        "Praktická doporučení pro český les, louky a pole (Podkrkonoší), složená z manuálu " +
            "V1.17 a vlastní zkušenosti. Nejsou to tovární presety Nokty."

    /** Run before touching anything else, on every new site. */
    val STARTUP_ROUTINE = listOf(
        "Načti správný preset.",
        "Noise Cancel / Frequency Shift.",
        "Ground Balance.",
        "Ground Tracking nech standardně OFF.",
        "Ujdi přibližně 20–30 m a sleduj stabilitu.",
        "Pokud je detektor hlučný, nejprve znovu Noise Cancel a Ground Balance.",
        "Teprve potom sniž citlivost o 1–2 body.",
        "Pokud za nestabilitu může mokrá/vodivá zem, přepni na M3.",
        "GS použij až jako poslední krok; začni hodnotou 1.",
    )

    val presets: List<SeedPreset> = listOf(
        SeedPreset(
            name = "Les",
            terrain = Terrain.LES,
            soil = SoilCondition.VLHKO,
            sensitivity = "25",
            groundBalance = "ručně; Ground Tracking vypnuto",
            discrimination = "A – All Metal",
            useCase =
            "Použití: běžný český les, lesní cesty, staré komunikace, okolí zaniklých cest a běžná " +
                "podkrkonošská půda, pokud není výrazně mokrá nebo nestabilní.",
            settings = """• Režim: FIELD
• Frekvence: M1
• Diskriminace: A – All Metal
• Citlivost: 25
• Recovery Speed: 4
• Iron Filter IF: 2
• Stability St: 2
• Bottle Cap bC: 0
• Ground Suppressor GS: 0
• Deep Target ID dt: 1
• Audio Gain AG: 4
• Počet tónů: 60
• Tone Volume Z1 – železo: 2
• Tone Volume Z2 – neželezo: 10
• Tone Frequency Z1: 2
• Tone Frequency Z2: 25
• Tone Break: 11
• Threshold: 0
• Ground Tracking: vypnuto""",
            why =
            "M1 je vhodný jako základ pro běžnou půdu a dává velmi dobré výsledky na střední a vyšší vodiče, " +
                "tedy typické mince a relikvie. Recovery 4 upřednostňuje hloubku před extrémní separací. IF 2 " +
                "nechává detektor poměrně otevřený, ale St 2 ho stále drží rozumně stabilní.",
            tuning = """• Je moc citlivý / náhodně pípá: nejdřív Noise Cancel + Ground Balance, potom Sens 24, případně 23.
• Je klidný a chceš víc dosahu: zkus Sens 26. Pokud přibydou falešné signály, vrať 25.
• Hodně železa: Recovery 5–6. Při silném maskování lze zkusit IF 1.
• Rezavé železo moc prozvukuje do barvy: St 3, případně IF zpět na 2–3.
• Slabé hluboké cíle špatně slyšíš: nech Recovery 4 a můžeš zkusit AG 5; počítej s větší „ukecaností“.
• Půda je mokrá nebo reaguje na cívku: přepni na LES – MOKRO / M3.
• Zem stále falešně reaguje i po GB: GS 1; GS 2 až když jednička nestačí.""",
        ),
        SeedPreset(
            name = "Les — mokro",
            terrain = Terrain.LES,
            soil = SoilCondition.MOKRO,
            sensitivity = "24",
            groundBalance = "ručně; Ground Tracking vypnuto",
            discrimination = "A – All Metal",
            useCase =
            "Použití: les po dešti, mokré podloží, vlhké údolnice, vodivější půda, mokrý jehličnatý les nebo " +
                "situace, kdy M1 začne dávat mnoho falešných zemních signálů.",
            settings = """• Režim: FIELD
• Frekvence: M3
• Diskriminace: A – All Metal
• Citlivost: 24
• Recovery Speed: 5
• Iron Filter IF: 2
• Stability St: 3
• Bottle Cap bC: 0
• Ground Suppressor GS: 0
• Deep Target ID dt: 0
• Audio Gain AG: 3
• Počet tónů: 60
• Tone Volume Z1 – železo: 2
• Tone Volume Z2 – neželezo: 10
• Tone Frequency Z1: 2
• Tone Frequency Z2: 25
• Tone Break: 11
• Threshold: 0
• Ground Tracking: vypnuto""",
            why =
            "M3 Nokta navrhla právě pro vlhké, mokré a vodivé půdy. Oproti M1 je zde cílem hlavně stabilita. " +
                "Proto je Recovery o stupeň výš, St 3 a dt vypnuté.",
            tuning = """• Pořád je hlučný: Sens 23, při opravdu problematické půdě 22.
• Zem pípá při dojezdu cívky k povrchu: zopakuj Ground Balance; potom GS 1.
• GS 1 nestačí: GS 2. Výš už jen výjimečně.
• M3 je až příliš klidná a půda už oschla: vrať se na M1.
• Chceš víc hloubky na čistém místě: Recovery 4, ale jen když stroj zůstane stabilní.
• Hodně železa: Recovery 6, IF 1–2 podle množství falešných barevných tónů.
• Slabé cíle jsou moc potichu: AG 4.""",
        ),
        SeedPreset(
            name = "Louka",
            terrain = Terrain.LOUKA,
            soil = SoilCondition.VLHKO,
            sensitivity = "26",
            groundBalance = "ručně; Ground Tracking vypnuto",
            discrimination = "A – All Metal",
            useCase =
            "Použití: louky, pastviny a travnaté plochy mimo moderní parky. Vhodné hlavně tam, kde očekáváš " +
                "starší mince a předměty uložené relativně hluboko v dlouhodobě nenarušené půdě.",
            settings = """• Režim: FIELD
• Frekvence: M1
• Diskriminace: A – All Metal
• Citlivost: 26
• Recovery Speed: 4
• Iron Filter IF: 2
• Stability St: 2
• Bottle Cap bC: 0
• Ground Suppressor GS: 0
• Deep Target ID dt: 1
• Audio Gain AG: 4
• Počet tónů: 60
• Tone Volume Z1 – železo: 2
• Tone Volume Z2 – neželezo: 10
• Tone Frequency Z1: 2
• Tone Frequency Z2: 25
• Tone Break: 11
• Threshold: 0
• Ground Tracking: vypnuto""",
            why =
            "Na relativně čisté louce můžeš využít nižší Recovery a o něco vyšší citlivost. Cílem je dobrý " +
                "dosah na hlubší mince a slabší signály. AG 4 slabé odezvy více vytáhne do zvuku, ale nezvyšuje " +
                "fyzickou hloubku detektoru.",
            tuning = """• Moc citlivý / falešné pípání: Sens 25, případně 24.
• Klidná louka: zkus Sens 27. Pokud je zvuk horší než na 26, vrať se.
• Louka je plná železa: Recovery 5–6.
• Je hodně moderního odpadu: zkus M2 a Recovery 5.
• Chceš maximum hloubky na čisté louce: Recovery 3–4, pomalejší vedení cívky, Sens jen tak vysoko, aby stroj zůstal klidný.
• Hluboké slabé signály jsou málo slyšet: AG 5.
• Po dešti nebo na mokré louce: přejdi na LOUKA – MOKRO.""",
        ),
        SeedPreset(
            name = "Louka — mokro",
            terrain = Terrain.LOUKA,
            soil = SoilCondition.MOKRO,
            sensitivity = "24–25",
            groundBalance = "ručně; Ground Tracking vypnuto",
            discrimination = "A – All Metal",
            useCase =
            "Použití: mokrá louka po dešti, podmáčená tráva, vlhká hlinitá půda, níže položená místa a situace, " +
                "kdy M1 začne reagovat na zem nebo vlhkost.",
            settings = """• Režim: FIELD
• Frekvence: M3
• Diskriminace: A – All Metal
• Citlivost: 24–25
• Recovery Speed: 5
• Iron Filter IF: 2
• Stability St: 3
• Bottle Cap bC: 0
• Ground Suppressor GS: 0
• Deep Target ID dt: 0
• Audio Gain AG: 3
• Počet tónů: 60
• Tone Volume Z1 – železo: 2
• Tone Volume Z2 – neželezo: 10
• Tone Frequency Z1: 2
• Tone Frequency Z2: 25
• Tone Break: 11
• Threshold: 0
• Ground Tracking: vypnuto""",
            why =
            "Mokrá louka umí být vodivější než stejná lokalita za sucha. M3 je vhodnější pro stabilitu na vlhké " +
                "půdě, Recovery 5 pomůže omezit zemní pazvuky a dt 0 drží odezvu čitelnější.",
            tuning = """• Je pořád moc citlivý: začni na Sens 24, pak 23.
• Je překvapivě klidný: můžeš zkusit Sens 25–26.
• Půda reaguje při každém zhoupnutí: zopakuj Ground Balance, pak GS 1.
• Chceš větší dosah na čistém místě: Recovery 4.
• Je hodně železa nebo drobného odpadu: Recovery 6.
• M3 působí zbytečně utlumeně a místo vysychá: vrať M1.
• Slabé cíle chceš více vytáhnout do zvuku: AG 4.""",
        ),
        SeedPreset(
            name = "Pole",
            terrain = Terrain.POLE,
            soil = SoilCondition.VLHKO,
            sensitivity = "25",
            groundBalance = "ručně; Ground Tracking vypnuto",
            discrimination = "A – All Metal",
            useCase =
            "Použití: oraná a obdělávaná pole, strniště a lokality s větším množstvím železa a drobných cílů. " +
                "Profil je nastaven tak, aby dobře reagoval i na malé a nižší vodiče.",
            settings = """• Režim: FIELD
• Frekvence: M2
• Diskriminace: A – All Metal
• Citlivost: 25
• Recovery Speed: 5
• Iron Filter IF: 2
• Stability St: 2
• Bottle Cap bC: 0
• Ground Suppressor GS: 0
• Deep Target ID dt: 1
• Audio Gain AG: 3
• Počet tónů: 60
• Tone Volume Z1 – železo: 2
• Tone Volume Z2 – neželezo: 10
• Tone Frequency Z1: 2
• Tone Frequency Z2: 25
• Tone Break: 11
• Threshold: 0
• Ground Tracking: vypnuto""",
            why =
            "M2 je dobrý univerzální základ pro pole, kde chceš zachovat citlivost na menší a nižší vodiče. " +
                "Recovery 5 je kompromis mezi separací v železe a dosahem. AG 3 zbytečně nezvýrazňuje všechny slabé " +
                "půdní a železné pazvuky.",
            tuning = """• Moc citlivý / skáče ID: Sens 24, případně 23.
• Čisté pole a cíle daleko od sebe: Recovery 4.
• Hodně železa: Recovery 6, případně IF 1 pro agresivnější odmaskování barvy mezi železem.
• IF 1 dává moc falešných barevných tónů: vrať IF 2 nebo St 3.
• Chceš hlavně větší/střední mince a vyšší vodiče: přepni M2 → M1.
• Chceš lépe slyšet slabé cíle: AG 4.
• Mokrá, vodivá půda: přepni na POLE – MOKRO / M3.""",
        ),
        SeedPreset(
            name = "Pole — mokro",
            terrain = Terrain.POLE,
            soil = SoilCondition.MOKRO,
            sensitivity = "24",
            groundBalance = "ručně; Ground Tracking vypnuto",
            discrimination = "A – All Metal",
            useCase =
            "Použití: mokré oranice, pole po dešti, těžší vodivá hlína, mazlavá půda a situace, kdy M2 začne " +
                "dávat falešné zemní signály.",
            settings = """• Režim: FIELD
• Frekvence: M3
• Diskriminace: A – All Metal
• Citlivost: 24
• Recovery Speed: 5
• Iron Filter IF: 2
• Stability St: 3
• Bottle Cap bC: 0
• Ground Suppressor GS: 0
• Deep Target ID dt: 0
• Audio Gain AG: 3
• Počet tónů: 60
• Tone Volume Z1 – železo: 2
• Tone Volume Z2 – neželezo: 10
• Tone Frequency Z1: 2
• Tone Frequency Z2: 25
• Tone Break: 11
• Threshold: 0
• Ground Tracking: vypnuto""",
            why =
            "M3 dává na mokré a vodivější půdě přednost stabilitě před maximální agresivitou. Na mokré oranici " +
                "je čitelný zvuk často cennější než o jeden až dva body vyšší citlivost.",
            tuning = """• Půda je pořád hlučná: Sens 23, pak GS 1.
• Velmi těžká mokrá zem: Recovery 6 může zlepšit čitelnost, ale trochu ubere hloubku.
• Pole je překvapivě čisté: Recovery 4 a Sens 25.
• Hodně železa: Recovery 6, IF 1–2.
• M3 je příliš utlumená a půda není ve skutečnosti vodivá: vrať se na M2.
• Po vyschnutí: standardně zpět M2 / Sens 25 / Recovery 5.
• Hluboké slabé cíle chceš více slyšet: AG 4, pokud tím nevznikne příliš mnoho pazvuků.""",
        ),
    )
}
