<!--
 This file is part of the Meeds project (https://meeds.io/).

 Copyright (C) 2020 - 2025 Meeds Association contact@meeds.io

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public
 License as published by the Free Software Foundation; either
 version 3 of the License, or (at your option) any later version.
 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 Lesser General Public License for more details.

 You should have received a copy of the GNU Lesser General Public License
 along with this program; if not, write to the Free Software Foundation,
 Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
-->
<template>
  <div
    v-if="supported"
    :style="containerStyle"
    class="d-flex flex-column align-center overflow-y-auto">
    <div v-if="loading" class="d-flex align-center justify-center fill-height">
      <v-progress-circular
        indeterminate
        color="primary"
        size="48" />
    </div>
    <template v-else>
      <canvas
        v-for="page in totalPages"
        :key="page"
        ref="pdfCanvas"
        :data-page="page"
        :style="canvasStyle(page)"
        class="elevation-1 mb-2"></canvas>
    </template>
  </div>
  <attachments-default-preview
    v-else
    :attachment="attachment" />
</template>
<script>
export default {
  props: {
    attachment: {
      type: Object,
      default: null,
    },
    objectType: {
      type: String,
      default: null,
    },
    objectId: {
      type: String,
      default: null,
    },
  },
  data: () => ({
    supported: true,
    loading: true,
    pdfDocument: null,
    totalPages: 0,
    pageAspectRatio: 1,
    renderedPages: {},
    observer: null,
    objectUrl: null,
  }),
  computed: {
    containerStyle() {
      return {height: !this.isMobile && '80vh' || '75vh'};
    },
    isMobile() {
      return this.$vuetify.breakpoint.name === 'sm' || this.$vuetify.breakpoint.name === 'xs' || this.$vuetify.breakpoint.name === 'md';
    },
    isPdf() {
      return this.attachment?.mimetype === 'application/pdf';
    },
  },
  mounted() {
    this.loadPdf();
  },
  beforeDestroy() {
    if (this.observer) {
      this.observer.disconnect();
      this.observer = null;
    }
    if (this.pdfDocument) {
      this.pdfDocument.destroy();
      this.pdfDocument = null;
    }
    this.revokeObjectUrl();
  },
  methods: {
    async loadPdf() {
      this.loading = true;
      this.supported = true;
      try {
        const source = this.isPdf ? this.attachment.downloadUrl : await this.fetchConvertedPdfUrl();
        this.pdfDocument = await window.pdfjsLib.getDocument(source).promise;
        this.totalPages = this.pdfDocument.numPages;
        const firstPage = await this.pdfDocument.getPage(1);
        const firstViewport = firstPage.getViewport(1);
        this.pageAspectRatio = firstViewport.height / firstViewport.width;
        firstPage.cleanup();
        this.loading = false;
        await this.$nextTick();
        this.observePages();
      } catch (e) {
        this.loading = false;
        this.supported = false;
      }
    },
    async fetchConvertedPdfUrl() {
      const response = await fetch(`/documents-preview/rest/documentspreview/${this.attachment.id}`, {
        method: 'GET',
        credentials: 'include',
      });
      if (!response || !response.ok) {
        throw new Error(`Error converting attachment '${this.attachment.id}' to PDF`);
      }
      const blob = await response.blob();
      this.revokeObjectUrl();
      this.objectUrl = URL.createObjectURL(blob);
      return this.objectUrl;
    },
    revokeObjectUrl() {
      if (this.objectUrl) {
        URL.revokeObjectURL(this.objectUrl);
        this.objectUrl = null;
      }
    },
    canvasStyle(page) {
      return this.renderedPages[page] && {maxWidth: '100%'} || {maxWidth: '100%', width: '100%', aspectRatio: `1 / ${this.pageAspectRatio}`};
    },
    observePages() {
      const canvases = this.$refs.pdfCanvas || [];
      this.observer = new IntersectionObserver(entries => {
        entries.forEach(entry => {
          if (entry.isIntersecting) {
            this.observer.unobserve(entry.target);
            this.renderPage(entry.target, Number(entry.target.dataset.page));
          }
        });
      }, {
        root: this.$el,
        rootMargin: '200px 0px',
      });
      canvases.forEach(canvas => this.observer.observe(canvas));
    },
    async renderPage(canvas, pageNumber) {
      if (!canvas || !this.pdfDocument || this.renderedPages[pageNumber]) {
        return;
      }
      const page = await this.pdfDocument.getPage(pageNumber);
      const containerWidth = canvas.parentElement?.clientWidth || 800;
      const viewport = page.getViewport(1);
      const scale = containerWidth / viewport.width;
      const scaledViewport = page.getViewport(scale);
      canvas.width = scaledViewport.width;
      canvas.height = scaledViewport.height;
      await page.render({canvasContext: canvas.getContext('2d'), viewport: scaledViewport}).promise;
      page.cleanup();
      this.$set(this.renderedPages, pageNumber, true);
    },
  },
};
</script>
