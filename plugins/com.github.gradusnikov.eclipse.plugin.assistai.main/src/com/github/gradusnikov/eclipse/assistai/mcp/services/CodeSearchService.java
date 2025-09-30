package com.github.gradusnikov.eclipse.assistai.mcp.services;

import org.eclipse.core.internal.resources.File;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.search.internal.ui.text.FileMatch;
import org.eclipse.search.internal.ui.text.FileSearchQuery;
import org.eclipse.search.internal.ui.text.LineElement;
import org.eclipse.search.ui.text.AbstractTextSearchResult;
import org.eclipse.search.ui.text.FileTextSearchScope;
import org.eclipse.search.ui.text.Match;

import jakarta.inject.Inject;

@Creatable
public class CodeSearchService {

	@Inject
	ILog logger;

	public String fileTextSearch(String searchText, boolean isRegEx, boolean isCaseSensitive, String fileNamePattern) {
		if (fileNamePattern == null) {
			fileNamePattern = "*";
		}


		StringBuilder result = new StringBuilder();
		
		result.append("# Search Results: ").append(searchText).append(" (").append(isRegEx ? "regex" : "plain text")
				.append(", ").append(isCaseSensitive ? "case-sensitive" : "case-insensitive").append(")\n");
		
		FileTextSearchScope searchScope = FileTextSearchScope.newWorkspaceScope(new String[] { fileNamePattern },
				false);
		
		FileSearchQuery query = new FileSearchQuery(searchText, isRegEx, isCaseSensitive, searchScope);

		query.run(new NullProgressMonitor());

		AbstractTextSearchResult searchResult = (AbstractTextSearchResult) query.getSearchResult();
		
		int fileCount = searchResult.getElements().length;
		int matchCount = 0;

		for (Object element : searchResult.getElements()) {
			matchCount += searchResult.getMatches(element).length;
		}
		
		result.append("Found ").append(matchCount).append(" matches in ").append(fileCount).append(" files\n\n");
		
		for (Object element : searchResult.getElements()) {
			File file = (File) element; 
			result.append("## ");
			result.append(file.getFullPath());
			result.append("\n");
			for (Match match : searchResult.getMatches(element)) {
				FileMatch fileMatch = (FileMatch) match;
				LineElement lineElement = fileMatch.getLineElement();
				int start = Math.max(fileMatch.getOriginalOffset() - lineElement.getOffset(), 0);
				int end = Math.min(
						fileMatch.getOriginalOffset() + fileMatch.getOriginalLength() - lineElement.getOffset(),
						lineElement.getLength());
				
				String content = fileMatch.getLineElement().getContents();
				
				result.append("- **");
				result.append(fileMatch.getLineElement().getLine());
				result.append("**: `");
				result.append(content.substring(0, start));
				result.append("**");
				result.append(content.substring(start, end));
				result.append("**");
				result.append(content.substring(end));
				result.append("`\n");
			}
			result.append("\n");
		}
		
		return result.isEmpty() ? "empty" : result.toString();
	}
}
