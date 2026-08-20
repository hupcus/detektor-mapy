package cz.hh.detektormapy.detector

import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain

/**
 * Rules of thumb that hold for detecting in general, independent of any particular machine.
 *
 * The hard line this object exists to keep: **no numbers**. Every sentence here describes what a
 * terrain or a soil condition does to a search -- iron density, how high the coil ends up riding,
 * whether the ground makes the machine falsify -- never a dial position. Sensitivity 1..10 on one
 * detector is 1..99 on another and "gain" on a third, so a number invented here would be a lie
 * dressed up as advice. Machine-specific values live in the user's own presets, and nowhere else.
 *
 * The wording is Czech because it is read by the user verbatim.
 */
object DetectorAdvice {

    /** Shown above the block so it can never be mistaken for settings of the user's machine. */
    const val DISCLAIMER: String =
        "Obecná pravidla, ne hodnoty pro tvůj stroj. Každý výrobce škáluje citlivost i rozlišení " +
            "jinak, takže konkrétní čísla dávají smysl jedině jako tvůj vlastní preset."

    /** What this terrain does to a search. */
    fun forTerrain(terrain: Terrain): List<String> = when (terrain) {
        Terrain.LES -> listOf(
            "Hlavní překážka v lese je železo — hřebíky, dráty, podkovy, plech. Pomalý záběr " +
                "pomáhá víc než cokoliv jiného: v hustém železe potřebuje detektor čas cíle oddělit.",
            "Kořeny, hrabanka a nerovný terén nutí držet cívku výš, než by bylo potřeba. Každý " +
                "centimetr navíc nad zemí je centimetr hloubky dole.",
            "Menší cívka se mezi kořeny a v železe vyplatí víc než velká: hůř se s ní dosahuje " +
                "do hloubky, ale lépe oddělí dva signály vedle sebe.",
        )

        Terrain.LOUKA -> listOf(
            "Louka bývá čistší než les, takže se dá jít výš s citlivostí a využít větší cívku.",
            "Hustý drn drží cívku nad zemí. Posekaná nebo spasená louka je lepší než vzrostlá " +
                "tráva a je to znát na hloubce.",
            "Trvalý travní porost znamená, že se v něm neoralo — nálezy tam bývají mělko a " +
                "zhruba tam, kde skončily, na rozdíl od pole.",
        )

        Terrain.POLE -> listOf(
            "Orba nálezy rozvleče a promíchá celou hloubkou ornice, takže jeden předmět bývá " +
                "rozptýlený a jinde, než kam původně padl.",
            "Nejlepší je čerstvě zorané, podmítnuté nebo uválené pole. Do vzrostlého porostu se " +
                "nechodí — kvůli hloubce i kvůli hospodáři.",
            "Hnojiva a mineralizovaná půda dělají falešné signály. Když stroj „mluví“ naprázdno, " +
                "uber citlivost a vyvaž znovu na zem; přebuzený detektor má menší reálný dosah než klidný.",
        )

        Terrain.ZAHRADA -> listOf(
            "Zahrady a dvory jsou plné moderního kovu a stavební suti. Víc než hloubka se tady " +
                "počítá schopnost oddělit dva signály hned vedle sebe.",
            "Plocha bývá malá, tak ji projdi pomalu a podruhé kolmo na první směr — co napoprvé " +
                "zapadlo do železa, se často ozve z druhé strany.",
            "Cizí zahrada je cizí pozemek. Bez svolení majitele tam detektor nepatří.",
        )

        Terrain.RUMISTE -> listOf(
            "Rumiště má nejhorší možný poměr signálů: hliník, plechovky, dráty a stavební " +
                "železo v každé vrstvě.",
            "Buď kopeš všechno na malé ploše, nebo si pomůžeš rozlišením a smíříš se s tím, že " +
                "o část dobrých cílů přijdeš. Nic mezi tím na navážce nefunguje.",
            "Sklo, plech a jehly: rukavice a pevná obuv, ne sandály.",
        )

        Terrain.PLAZ -> listOf(
            "Rozhoduje, jestli detektor umí slanou vodu. Suchý písek nad čárou zvládne každý " +
                "stroj, ale mokrý slaný písek a příboj rozhodí VLF přístroj bez režimu na pláž.",
            "U nás jsou pláže sladkovodní, takže tenhle problém většinou odpadá a písek se chová " +
                "jako obyčejná mokrá půda.",
            "Slaná voda ničí techniku. Cívku, tyč i konektory po každém dni u moře opláchni " +
                "sladkou vodou.",
        )
    }

    /** What this soil condition does to a search. */
    fun forSoil(soil: SoilCondition): List<String> = when (soil) {
        SoilCondition.SUCHO -> listOf(
            "V suché zemi bývají signály kratší a tišší a hlubší cíle se ztrácejí dřív. Není to " +
                "důvod přidat citlivost — spíš jít pomaleji a nepřehlížet slabé signály.",
            "Vyprahlá zem je tvrdá na kopání a špatně se zahlazuje. Na cizím trávníku je sucho " +
                "ten nejhorší čas.",
        )

        SoilCondition.VLHKO -> listOf(
            "Vlhká, ale ne rozbahněná půda je nejlepší kompromis: signály jsou čitelné, zem jde " +
                "vykopnout a drn se dá vrátit tak, aby to nebylo poznat.",
            "Když se dá vybrat, jdi den nebo dva po dešti, ne během něj.",
        )

        SoilCondition.MOKRO -> listOf(
            "Nasáklá zem je dvojsečná: málo mineralizované půdě vlhkost spíš pomáhá, ale silně " +
                "mineralizovanou nebo slanou půdu rozmáčení rozhodí a detektor začne „mluvit“.",
            "Když stroj v mokru falšuje, řešení je nižší citlivost a nové vyvážení na zem, ne " +
                "opačně.",
            "V rozbahněném poli se navíc nedá slušně zahladit důlek a auto tam nemá co dělat.",
        )
    }
}
