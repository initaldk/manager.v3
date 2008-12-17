// Copyright 2006 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.spi;

import java.util.Collection;

/**
 * Authorization Manager. All calls related to authorizing particular users
 * to see particular documents pass through this interface.
 */
public interface AuthorizationManager {

  /**
   * Gets authorization from the repository for a set of documents by ID.
   * 
   * @param docids The document set represented as a Collection of Strings:
   *        the docid for each document. The Connector Manager will ensure that
   *        no docid is repeated in this collection.  The docid strings which the
   *        connector manager will pass in should have come from the search
   *        appliance, from documents that this connector submitted for
   *        indexing via traversal. Thus the docids should have started as
   *        {@link SpiConstants}.PROPNAME_DOCID properties that were part
   *        of an {@link DocumentList} returned from a
   *        {@link TraversalManager}.startTraversal or
   *        {@link TraversalManager}.resumeTraversal call.
   * @param identity The user's identity, as an
   *        {@link AuthenticationIdentity}
   * @return A Collection of {@link AuthorizationResponse} objects, one for each
   *         docid in the docidList parameter; however, this list does not
   *         need to be in the same order.
   * @throws RepositoryException
   */
  Collection authorizeDocids(Collection docids, AuthenticationIdentity identity)
      throws RepositoryException;
}
