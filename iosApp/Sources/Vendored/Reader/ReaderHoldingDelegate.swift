//
//  ReaderHoldingDelegate.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 8/16/22.
//

import Foundation
import ExtensionRunner

protocol ReaderHoldingDelegate: AnyObject {
    var barsHidden: Bool { get }

    func hideBars()

    func getNextChapter() -> ExtensionRunner.Chapter?
    func getPreviousChapter() -> ExtensionRunner.Chapter?
    func setChapter(_ chapter: ExtensionRunner.Chapter)

    func setCurrentPage(_ page: Int, position: Double?)
    func setCurrentPages(_ pages: ClosedRange<Int>)
    func setPages(_ pages: [Page], error: Error?)
    func displayPage(_ page: Int) // show page on toolbar but don't set it as current page
    func setSliderOffset(_ offset: CGFloat)
    func setCompleted()
}

extension ReaderHoldingDelegate {
    func setPages(_ pages: [Page]) {
        setPages(pages, error: nil)
    }
}
