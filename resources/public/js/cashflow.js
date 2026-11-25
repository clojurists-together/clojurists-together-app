(function () {
  document.querySelectorAll('.forecast-scroll').forEach(function (viewport) {
    var table = viewport.querySelector('table');
    var header = table.querySelector('thead');
    var floating = document.createElement('div');
    floating.className = 'forecast-floating-header';
    floating.setAttribute('aria-hidden', 'true');
    floating.inert = true;
    floating.hidden = true;
    var copy = document.createElement('table');
    var columns = document.createElement('colgroup');
    var cells = [header.querySelector('.row-label')].concat(
      Array.from(header.querySelectorAll('tr:last-child th'))
    );
    cells.forEach(function () { columns.appendChild(document.createElement('col')); });
    copy.appendChild(columns);
    copy.appendChild(header.cloneNode(true));
    floating.appendChild(copy);
    document.body.appendChild(floating);

    function positionHeader() {
      var rect = table.getBoundingClientRect();
      var frame = viewport.getBoundingClientRect();
      var height = header.getBoundingClientRect().height;
      floating.hidden = rect.top >= 0 || rect.bottom <= 0;
      if (!floating.hidden) {
        floating.style.left = frame.left + 'px';
        floating.style.width = viewport.clientWidth + 'px';
        floating.style.top = Math.min(0, rect.bottom - height) + 'px';
        floating.scrollLeft = viewport.scrollLeft;
      }
    }

    function sizeHeader() {
      cells.forEach(function (cell, index) {
        columns.children[index].style.width = cell.getBoundingClientRect().width + 'px';
      });
      copy.style.width = table.getBoundingClientRect().width + 'px';
      positionHeader();
    }

    var pending = false;
    function schedulePosition() {
      if (pending) return;
      pending = true;
      requestAnimationFrame(function () { pending = false; positionHeader(); });
    }
    window.addEventListener('scroll', schedulePosition, {passive: true});
    window.addEventListener('resize', sizeHeader);
    viewport.addEventListener('scroll', schedulePosition, {passive: true});
    new ResizeObserver(sizeHeader).observe(table);

    var current = header.querySelector('.month-current');
    var label = header.querySelector('.row-label');
    if (current && label) {
      viewport.scrollLeft = Math.max(0, current.getBoundingClientRect().left -
        viewport.getBoundingClientRect().left - label.getBoundingClientRect().width + viewport.scrollLeft);
    }
    sizeHeader();
  });
}());
