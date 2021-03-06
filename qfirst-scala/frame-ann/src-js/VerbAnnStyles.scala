package qfirst.frame.ann

import scalacss.DevDefaults._
import scala.language.postfixOps

object VerbAnnStyles extends StyleSheet.Inline {
  import dsl._

  // color scheme

  val headerBackgroundColor = grey(240)
  val headerContentColor = black

  val labeledHighlightColor = rgba(  0, 128, 256, 0.1)
  val selectedHighlightColor = grey(200)
  val hoverHighlightColor = grey(240)
  val alternatingRowBackgroundColor1 = white
  val alternatingRowBackgroundColor2 = grey(240)

  val originalRoundIndicatorColor = grey(200)
  val expansionRoundIndicatorColor = rgba(  64, 192,   0, 1.0)
  val evalRoundIndicatorColor = orange

  val metadataLabelBackgroundColor = grey(240)

  val validTextColor = green
  val invalidTextColor = red

  val paneDivisionBorderWidth = 1 px
  val paneDivisionBorderColor = metadataLabelBackgroundColor

  val documentSelectionPaneBorder = style(
    borderLeftColor(paneDivisionBorderColor),
    borderLeftStyle.solid,
    borderLeftWidth(paneDivisionBorderWidth),
  )

  val sentenceSelectionPaneBorder = style(
    borderLeftColor(paneDivisionBorderColor),
    borderRightColor(paneDivisionBorderColor),
    borderLeftStyle.solid,
    borderRightStyle.solid,
    borderLeftWidth(paneDivisionBorderWidth),
    borderRightWidth(paneDivisionBorderWidth)
  )

  val auditingPaneBorder = style(
    borderTopColor(paneDivisionBorderColor),
    borderTopStyle.solid,
    borderTopWidth(paneDivisionBorderWidth),

  )

  // styles

  val checkbox = style(
    addClassNames("form-check-input")
  )

  val checkboxLabel = style(
    addClassNames("form-check-label")
  )

  val webkitScrollbar = {
    import scalacss.internal._
    Cond(Some(Pseudo.Custom("::-webkit-scrollbar", PseudoType.Element)), Vector.empty)
  }

  val mainContainer = style(
    addClassNames("container-fluid", "p-0", "m-0")
  )

  // header

  val headerHeight = 30 px

  val flexyHeaderThing = style(
    display.flex,
    flexDirection.row,
    flexWrap.nowrap,
    position.relative,
    zIndex(10)
  )

  val headerContainer = style(
    addClassNames("p-2"),
    flexyHeaderThing,
    alignItems.center,
    height(headerHeight),
    backgroundColor(headerBackgroundColor),
    color(headerContentColor),
  )

  val titleAndSearchContainer = style()

  // title

  val title = style(
    whiteSpace.nowrap,
    overflow.hidden
  )

  // search

  val searchContainer = style(
    flexyHeaderThing,
    width(100 %%)
  )

  val searchInput = style(
    flex := "1"
  )
  val searchSubmitButton = style()
  val searchClearButton = style()

  // filters

  val filterContainer = style(
    addClassNames("px-4"),
    flexyHeaderThing
  )

  val filterChooser = style(
    addClassNames("form-check", "pl-5")
  )

  val partitionChooser = style(
    filterChooser
  )

  val domainChooser = style(
    filterChooser
  )

  val sliceChooser = style(
    filterChooser
  )

  // legend

  val legendContainer = style(
    addClassNames("pt-1"),
    minWidth(350 px)
  )

  val legendTitle = style()

  val legendTitleText = style(
    fontWeight.bold
  )
  val legendTitleLinkText = style()

  val validityLegend = style()

  val highlightLegend = style()

  val legendColorIndicator = style()

  // help modal

  val helpModal = style(
    addClassNames("modal", "fade")
  )
  val helpModalDialog = style(
    addClassNames("modal-dialog"),
    maxWidth(800 px)
  )
  val helpModalContent = style(
    addClassNames("modal-content")
  )
  val helpModalHeader = style(
    addClassNames("modal-header")
  )
  val helpModalTitle = style(
    addClassNames("modal-title"),
    fontWeight.bold,
    fontSize(16 pt)
  )
  val helpModalHeaderCloseButton = style(
    addClassNames("close")
  )
  val helpModalBody = style(
    addClassNames("modal-body")
  )
  val helpModalFooter = style(
    addClassNames("modal-footer")
  )
  val helpModalFooterCloseButton = style(
    addClassNames("btn", "btn-secondary")
  )

  val helpWarningAlert = style(
    addClassNames("alert", "alert-warning")
  )

  // main data display

  val dataContainer = style(
    position.relative,
    overflow.hidden,
    backfaceVisibility.hidden,
    willChange := "overflow",
    display.flex,
    height(100 vh),
    marginTop(-headerHeight),
    paddingTop(headerHeight),
    width(100 %%)
  )

  val scrollPane = style(
    overflow.auto,
    height.auto,
    webkitScrollbar(
      display.none
    )
    // attr("-webkit-overflow-scrolling") := "touch",
    // attr("-ms-overflow-style") := "none"
  )

  // selection of sentences

  val metadataLabelHeight = 1 rem
  val metadataLabelFontSize = 8 pt

  val metadataLabel = style(
    display.block,
    height(metadataLabelHeight),
    backgroundColor(metadataLabelBackgroundColor),
    fontSize(metadataLabelFontSize),
    verticalAlign.middle
  )
  val metadataLabelText = style(
    addClassNames("px-1"),
    whiteSpace.nowrap
  )

  val documentSelectionPaneWidth = 10 rem
  val sentenceSelectionPaneWidth = 12 rem

  val documentSelectionFontSize = 12 pt
  val sentenceSelectionFontSize = 10 pt

  val contentPaneContainer = style(
    position.relative,
    overflow.hidden,
    backfaceVisibility.hidden,
    willChange := "overflow",
    display.flex,
    flexDirection.column
  )

  val selectionPane = style(
    scrollPane,
    lineHeight(1.2)
  )

  val countLabel = style(
    metadataLabel,
    textAlign.right
  )
  val countLabelText = style(
    metadataLabelText
  )

  val selectionEntry = style(
    addClassNames("p-2"),
    &.hover(
      backgroundColor(hoverHighlightColor)
    )
  )

  val currentSelectionEntry = style(
    selectionEntry,
    backgroundColor(selectedHighlightColor).important
  )

  val nonCurrentSelectionEntry = style(
    selectionEntry
  )

  val documentSelectionPaneContainer = style(
    contentPaneContainer,
    documentSelectionPaneBorder,
    width(documentSelectionPaneWidth),
  )

  val documentCountLabel = style(
    countLabel
  )

  val documentCountLabelText = style(
    countLabelText
  )

  val documentSelectionPane = style(
    selectionPane,
    width(100 %%)
  )

  val documentSelectionEntry = style(
    selectionEntry
  )

  val documentSelectionEntryText = style(
    fontSize(documentSelectionFontSize)
  )

  val sentenceSelectionPaneContainer = style(
    contentPaneContainer,
    sentenceSelectionPaneBorder,
    width(sentenceSelectionPaneWidth)
  )

  val sentenceCountLabel = style(
    countLabel
  )

  val sentenceCountLabelText = style(
    countLabelText
  )

  val sentenceSelectionPane = style(
    selectionPane,
  )

  val sentenceSelectionEntry = style(
    selectionEntry,
  )

  val labeledDocumentSelectionEntry = style(
    selectionEntry,
    backgroundColor(labeledHighlightColor)
  )

  val labeledSentenceSelectionEntry = style(
    selectionEntry,
    backgroundColor(labeledHighlightColor)
  )

  val sentenceSelectionEntryText = style(
    fontSize(sentenceSelectionFontSize),
  )

  // display of document biggy thing

  val documentContainer = style(
    flex := "1",
    display.flex,
    flexDirection.row,
    overflow.hidden,
    position.relative,
    backfaceVisibility.hidden,
    willChange := "overflow"
  )

  // display of sentence data

  val sentenceDisplayPane = style(
    contentPaneContainer,
    flex := "1"
  )

  val sentenceInfoContainer = style(
    addClassNames("pl-2"),
    metadataLabel,
    textAlign.left
  )
  val sentenceInfoText = style(
    metadataLabelText
  )

  val sentenceTextContainer = style(
    addClassNames("p-3"),
  )

  val verbAnchorLink = style(
    &.hover(
      textDecoration := "none"
    )
  )

  val verbEntriesContainer = style(
    scrollPane,
    flex := "1"
  )

  val loadingNotice = style(
    addClassNames("p-3")
  )

  val sentenceText = style(
    fontSize(16 pt)
  )

  val verbEntryDisplay = style(
    addClassNames("px-4", "pb-4"),
    width(100 %%)
  )

  val verbHeading = style()

  val verbHeadingText = style(
    fontSize(16 pt),
    fontWeight.bold
  )

  val verbQAsTable = style(
    width(100 %%)
  )

  val verbQAsTableBody = style(
    width(100 %%)
  )

  val hoverHighlightedVerbTable = style(
    // backgroundColor(hoverHighlightColor)
  )

  val qaPairRow = style(
    addClassNames("p-1"),
    width(100 %%),
    // height(2 em)
    // &.nthChild("odd")(
    //   backgroundColor(alternatingRowBackgroundColor1)
    // ),
    // &.nthChild("even")(
    //   backgroundColor(alternatingRowBackgroundColor2)
    // )
  )

  val roundIndicator = style(
    width(0.2 rem),
    height(100 %%)
  )

  val originalRoundIndicator = style(
    roundIndicator,
    backgroundColor(originalRoundIndicatorColor)
  )

  val expansionRoundIndicator = style(
    roundIndicator,
    backgroundColor(expansionRoundIndicatorColor)
  )

  val evalRoundIndicator = style(
    roundIndicator,
    backgroundColor(evalRoundIndicatorColor)
  )

  // detour to legend real quick

  val roundLegendMark = style(
    addClassNames("ml-2"),
    display.inlineBlock,
    color.transparent
  )

  val originalLegendMark = style(
    roundLegendMark,
    originalRoundIndicator
  )

  val expansionLegendMark = style(
    roundLegendMark,
    expansionRoundIndicator
  )

  val evalLegendMark = style(
    roundLegendMark,
    evalRoundIndicator
  )

  // back to table (question cells etc)

  val questionCellPadding = style(
    addClassNames("pl-1", "py-2"),
  )

  val questionCell = style(
    questionCellPadding,
    width(16 rem)
  )
  val questionText = style()

  val validityCell = style(
    addClassNames("px-2"),
    width(2 rem)
  )
  val validityText = style()
  val validValidityText = style(
    validityText,
    color(validTextColor)
  )
  val invalidValidityText = style(
    validityText,
    color(invalidTextColor)
  )

  val answerCell = style()
  val answerText = style()

  val qaPairDisplay = style()

  val questionFullDescriptionCell = style(
    padding(0 px),
    margin(0 px)
  )

  val questionSourceText = style(
    questionCellPadding
  )

  val answerSourceIdCell = style(
    width(15 rem)
  )

  val dummyRow = style(
    margin(0 px),
    padding(0 px)
  )

  val verbDropdown = style()
  val frameContainer = style(
    addClassNames("p-3"),
    width(40 rem)
  )
  val frameHeading = verbHeading
  val frameHeadingText = verbHeadingText
  val clauseSetDisplay = style(
    addClassNames("p-1")
  )
  val mutedControl = style(
    color(grey(200)),
    &.hover(
      fontWeight.bold,
      color(black)
    )
  )
  val addClauseOption = style()
  val addFrameOption = mutedControl
  val frameDeleteText = mutedControl
  val frameLinkAllValidText = mutedControl
  val clauseDeleteText = mutedControl
  val candidateClauseChoice = style(
    backgroundColor(rgba(256, 128, 0, 0.5))
  )
  val coveredClause = style(
    backgroundColor(rgba(0, 128, 256, 0.25))
  )
  val hoveredClause = style(
    backgroundColor(rgba(64, 192, 0, 0.4))
  )

  val coveredQuestion = coveredClause
  val hoveredQuestion = hoveredClause

  val frameSpecDisplay = style(
    height(70 vh)
  )
  val frameAuditingDisplay = style(
    height(30 vh),
    auditingPaneBorder
  )
  val singleFrameAuditingDisplay = style()
  val prevFrameInstanceText = mutedControl
  val nextFrameInstanceText = mutedControl
  val substitutedArgString = style(
    fontWeight.bold
  )
  val argPlaceholder = style()
  val argSigil = style(
    fontWeight.bold
  )
  val sentenceLink = style()


  val goDisplay = style(
    addClassNames("mx-2")
  )
  val goLabelText = style()
  val goTextField = style()
}
